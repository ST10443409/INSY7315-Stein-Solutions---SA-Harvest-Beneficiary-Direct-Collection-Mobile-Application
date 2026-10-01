using api.Data;
using api.Models;
using api.Services.Foodspace;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace api.Services;

public record VettingRecordsQuery(int Page, int PageSize, string? Province);

/// <summary>One page of beneficiary records plus what the app needs to page through and judge freshness.</summary>
/// <param name="FetchedAt">When this backend last got the list from Foodspace.</param>
/// <param name="Stale">True when Foodspace could not be reached to refresh an expired cache, so this is the last copy we have.</param>
public record VettingRecordsPage(
    IReadOnlyList<FoodspaceBeneficiaryRecord> Items, int Page, int PageSize, int TotalCount, bool HasMore,
    DateTimeOffset FetchedAt, bool Stale);

/// <summary>Null <see cref="Page"/> means there is nothing cached and Foodspace could not be reached.</summary>
public record VettingRecordsResult(VettingRecordsPage? Page);

public interface IVettingRecordsService
{
    Task<VettingRecordsResult> GetPageAsync(VettingRecordsQuery query, CancellationToken cancellationToken = default);
}

/// <summary>
/// Serves the beneficiary records a vetting officer reviews, from a cache of Foodspace's list.
///
/// Why a cache: Foodspace's beneficiary endpoint cannot page or filter by date (see docs/OPEN-DECISIONS.md), so paging
/// straight through to it would download the whole set for every page of every officer. Instead the full list is fetched
/// from Foodspace at most once per <see cref="FoodspaceOptions.BeneficiaryCacheMinutes"/>, replaces the table
/// <c>foodspace_beneficiary_records</c> (fetch-and-replace, as that table is documented), and pages are cut from the table.
///
/// If Foodspace is down the last copy is still served, flagged <see cref="VettingRecordsPage.Stale"/>: an officer about to
/// go offline gets the records we have rather than an error. Only when nothing was ever cached is there no result.
/// </summary>
public class VettingRecordsService : IVettingRecordsService
{
    // One refresh at a time per process, so a burst of officers syncing together asks Foodspace once.
    private static readonly SemaphoreSlim RefreshGate = new(1, 1);

    private readonly AppDbContext _db;
    private readonly IFoodspaceApiClient _client;
    private readonly FoodspaceOptions _options;
    private readonly TimeProvider _time;
    private readonly ILogger<VettingRecordsService> _logger;

    public VettingRecordsService(
        AppDbContext db, IFoodspaceApiClient client, IOptions<FoodspaceOptions> options, TimeProvider time,
        ILogger<VettingRecordsService> logger)
    {
        _db = db;
        _client = client;
        _options = options.Value;
        _time = time;
        _logger = logger;
    }

    public async Task<VettingRecordsResult> GetPageAsync(VettingRecordsQuery query, CancellationToken cancellationToken = default)
    {
        var fetchedAt = await LastFetchedAtAsync(cancellationToken);
        var stale = false;

        if (IsExpired(fetchedAt))
        {
            // The refresh itself reports its time: an empty (but valid) Foodspace list leaves no rows to read it from.
            var refreshed = await RefreshIfStillExpiredAsync(cancellationToken);
            if (refreshed is not null) fetchedAt = refreshed;
            else stale = true;
        }

        if (fetchedAt is null) return new VettingRecordsResult(null); // never cached, and Foodspace is not answering

        var records = _db.FoodspaceBeneficiaryRecords.AsNoTracking().AsQueryable();
        if (!string.IsNullOrWhiteSpace(query.Province))
        {
            var province = query.Province.Trim().ToLower();
            records = records.Where(r => r.Province.ToLower() == province);
        }

        var total = await records.CountAsync(cancellationToken);
        var items = await records
            .OrderBy(r => r.Id) // stable across requests, so no record is skipped or repeated between pages
            .Skip((query.Page - 1) * query.PageSize)
            .Take(query.PageSize)
            .ToListAsync(cancellationToken);

        var hasMore = (long)query.Page * query.PageSize < total;
        return new VettingRecordsResult(new VettingRecordsPage(items, query.Page, query.PageSize, total, hasMore, fetchedAt.Value, stale));
    }

    private async Task<DateTimeOffset?> LastFetchedAtAsync(CancellationToken cancellationToken) =>
        await _db.FoodspaceBeneficiaryRecords.AsNoTracking().MaxAsync(r => (DateTimeOffset?)r.FetchedAt, cancellationToken);

    private bool IsExpired(DateTimeOffset? fetchedAt) =>
        fetchedAt is null || _time.GetUtcNow() - fetchedAt.Value >= TimeSpan.FromMinutes(_options.BeneficiaryCacheMinutes);

    /// <summary>Returns when the cache was last refreshed if it is fresh afterwards (refreshed here, or by a request that was ahead of us), otherwise null.</summary>
    private async Task<DateTimeOffset?> RefreshIfStillExpiredAsync(CancellationToken cancellationToken)
    {
        await RefreshGate.WaitAsync(cancellationToken);
        try
        {
            // Another request may have refreshed while we waited for the gate.
            var current = await LastFetchedAtAsync(cancellationToken);
            if (!IsExpired(current)) return current;
            return await RefreshAsync(cancellationToken);
        }
        finally
        {
            RefreshGate.Release();
        }
    }

    private async Task<DateTimeOffset?> RefreshAsync(CancellationToken cancellationToken)
    {
        var fetch = await _client.GetBeneficiariesAsync(cancellationToken);
        if (fetch.Outcome != FoodspaceOutcome.Success)
        {
            _logger.LogWarning("Beneficiary refresh failed ({Error}); serving the cached copy if there is one.", fetch.Error);
            return null;
        }
        if (fetch.Records.Count == 0 && fetch.Skipped > 0)
        {
            // Everything Foodspace sent was unreadable: replacing the cache would wipe good data with nothing.
            _logger.LogWarning("Foodspace sent {Skipped} beneficiary record(s), none readable; keeping the cached copy.", fetch.Skipped);
            return null;
        }

        var now = _time.GetUtcNow();
        var incoming = fetch.Records.GroupBy(r => r.Id).Select(g => g.Last()).ToList(); // the same id twice: the last one wins
        var existing = await _db.FoodspaceBeneficiaryRecords.ToDictionaryAsync(r => r.Id, cancellationToken);

        foreach (var record in incoming)
        {
            record.FetchedAt = now;
            if (existing.Remove(record.Id, out var current)) _db.Entry(current).CurrentValues.SetValues(record);
            else _db.FoodspaceBeneficiaryRecords.Add(record);
        }
        _db.FoodspaceBeneficiaryRecords.RemoveRange(existing.Values); // no longer in Foodspace's list

        await _db.SaveChangesAsync(cancellationToken); // one save: readers see the old list or the new one, never half of each
        _logger.LogInformation("Beneficiary cache replaced: {Count} record(s), {Removed} removed.", incoming.Count, existing.Count);
        return now;
    }
}
