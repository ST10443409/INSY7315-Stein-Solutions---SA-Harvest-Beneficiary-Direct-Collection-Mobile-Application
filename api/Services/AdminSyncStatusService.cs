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
/// Counts the records this backend holds by <see cref="SyncState"/>, for the Admin sync monitor (#49). Counts only:
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
        // The state of every bucket comes from the one definition (SyncStates.Of), so this and the failed-sync list agree.
        var byState = buckets
            .GroupBy(b => SyncStates.Of(b.Status, b.HasRetry, b.IsDuplicate))
            .ToDictionary(g => g.Key, g => g.Sum(b => b.Count));
        int Of(SyncState state) => byState.GetValueOrDefault(state);

        return new FormSyncCounts(
            Total: buckets.Sum(b => b.Count),
            Waiting: Of(SyncState.Waiting),
            Retrying: Of(SyncState.Retrying),
            NeedsAttention: Of(SyncState.NeedsAttention),
            Forwarded: Of(SyncState.Forwarded),
            Duplicates: Of(SyncState.DuplicateHeld),
            Superseded: Of(SyncState.Superseded),
            Dismissed: Of(SyncState.Dismissed));
    }
}
