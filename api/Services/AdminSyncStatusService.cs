using api.Data;
using api.DTOs;
using api.Models;
using Microsoft.EntityFrameworkCore;

namespace api.Services;

public interface IAdminSyncStatusService
{
    Task<AdminSyncStatusResponse> GetAsync(CancellationToken cancellationToken = default);
}

/// <summary>
/// Counts the records this backend holds by where they stand with Foodspace, for the Admin sync monitor (#49). Counts only:
/// no record data leaves here. The groups are decided in one query per form so the numbers always add up.
/// </summary>
public class AdminSyncStatusService : IAdminSyncStatusService
{
    private readonly AppDbContext _db;
    private readonly TimeProvider _time;

    public AdminSyncStatusService(AppDbContext db, TimeProvider time)
    {
        _db = db;
        _time = time;
    }

    public async Task<AdminSyncStatusResponse> GetAsync(CancellationToken cancellationToken = default)
    {
        var collections = await _db.CboCollections
            .GroupBy(c => new { c.ForwardingStatus, HasRetry = c.NextForwardAttemptAt != null, IsDuplicate = c.DuplicateOfId != null })
            .Select(g => new Bucket(g.Key.ForwardingStatus, g.Key.HasRetry, g.Key.IsDuplicate, g.Count()))
            .ToListAsync(cancellationToken);

        var decisions = await _db.VettingDecisions
            .GroupBy(d => new { d.ForwardingStatus, HasRetry = d.NextForwardAttemptAt != null })
            .Select(g => new Bucket(g.Key.ForwardingStatus, g.Key.HasRetry, false, g.Count()))
            .ToListAsync(cancellationToken);

        return new AdminSyncStatusResponse(Tally(collections), Tally(decisions), _time.GetUtcNow());
    }

    private sealed record Bucket(ForwardingStatus Status, bool HasRetry, bool IsDuplicate, int Count);

    private static FormSyncCounts Tally(IReadOnlyList<Bucket> buckets)
    {
        int Sum(Func<Bucket, bool> where) => buckets.Where(where).Sum(b => b.Count);

        // A suspected duplicate is its own state whatever its forwarding status says; everything else falls into one other state.
        return new FormSyncCounts(
            Total: Sum(_ => true),
            Waiting: Sum(b => !b.IsDuplicate && b.Status == ForwardingStatus.Pending),
            Retrying: Sum(b => !b.IsDuplicate && b.Status == ForwardingStatus.SyncedLocalPendingFoodspace && b.HasRetry),
            NeedsAttention: Sum(b => !b.IsDuplicate && b.Status == ForwardingStatus.SyncedLocalPendingFoodspace && !b.HasRetry),
            Forwarded: Sum(b => !b.IsDuplicate && b.Status == ForwardingStatus.Forwarded),
            Duplicates: Sum(b => b.IsDuplicate),
            Superseded: Sum(b => !b.IsDuplicate && b.Status == ForwardingStatus.Superseded));
    }
}
