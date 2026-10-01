using api.Data;
using api.DTOs;
using api.Models;
using Microsoft.EntityFrameworkCore;

namespace api.Services;

public interface IVettingDecisionIngestionService
{
    /// <summary>
    /// Validates and stores a batch of decisions made by <paramref name="officer"/>. Always returns one result per
    /// submitted record, in the same order.
    /// </summary>
    Task<IReadOnlyList<VettingSyncResult>> IngestAsync(
        IReadOnlyList<VettingDecisionSyncItemDto> records, string officer, CancellationToken cancellationToken = default);
}

/// <summary>
/// Stores vetting decisions sent by the Android app. The same shape as <see cref="CboCollectionIngestionService"/>
/// (one result per record, in order; each record succeeds or fails on its own; idempotent on the client id), applied to
/// decisions:
///
/// * RETRY: the same client id sent again (the first response was probably lost). Nothing is written, the record is a
///   success with AlreadyReceived = true, and the first write wins. The one exception: an id already stored for a
///   DIFFERENT officer is refused as invalid. A UUID does not collide by accident, so that is a bug or an attempt to
///   claim someone else's decision, and reporting it as "received" would let the device discard a decision we never stored.
/// * The officer is the signed-in user, taken from the token. Whatever the device sent as officerId is replaced.
/// * The beneficiary is NOT checked against our cached copy of Foodspace's list. That copy is replaced on every refresh
///   and may be empty or a few minutes behind, so refusing on it would permanently reject honest decisions. Foodspace is
///   the authority: if it does not know the beneficiary, forwarding fails permanently and the decision waits, stored and
///   visible, for an Admin (it is never lost).
/// * Several decisions on one beneficiary are all kept (the officer may change their mind). Only the newest is forwarded;
///   see <see cref="Foodspace.VettingDecisionForwarder"/>.
/// </summary>
public class VettingDecisionIngestionService : IVettingDecisionIngestionService
{
    private readonly AppDbContext _db;
    private readonly ILogger<VettingDecisionIngestionService> _logger;

    public VettingDecisionIngestionService(AppDbContext db, ILogger<VettingDecisionIngestionService> logger)
    {
        _db = db;
        _logger = logger;
    }

    public async Task<IReadOnlyList<VettingSyncResult>> IngestAsync(
        IReadOnlyList<VettingDecisionSyncItemDto> records, string officer, CancellationToken cancellationToken = default)
    {
        var results = new VettingSyncResult?[records.Count];
        var toStore = new List<(int Index, VettingDecision Entity)>();

        // 1. Validate every record on its own.
        var validIds = new List<string>();
        for (var i = 0; i < records.Count; i++)
        {
            var errors = records[i] is null ? new List<string> { "Record is missing." } : VettingDecisionSyncValidator.Validate(records[i]);
            if (errors.Count > 0)
                results[i] = Invalid(records[i]?.Id, string.Join(" ", errors));
            else
                validIds.Add(records[i].Id!);
        }

        // 2. Ids already stored are retries.
        var existing = await _db.VettingDecisions.AsNoTracking()
            .Where(d => validIds.Contains(d.Id))
            .Select(d => new { d.Id, d.OfficerId })
            .ToDictionaryAsync(d => d.Id, d => d.OfficerId, cancellationToken);

        var seenInBatch = new HashSet<string>();
        for (var i = 0; i < records.Count; i++)
        {
            if (results[i] is not null) continue;
            var id = records[i].Id!;

            if (existing.TryGetValue(id, out var storedOfficer))
            {
                results[i] = storedOfficer == officer
                    ? new VettingSyncResult(id, true, AlreadyReceived: true)
                    : Invalid(id, "This id belongs to another officer's decision.");
                continue;
            }
            if (!seenInBatch.Add(id))
            {
                results[i] = new VettingSyncResult(id, true, AlreadyReceived: true); // same id twice in one batch
                continue;
            }
            toStore.Add((i, ToEntity(records[i], officer)));
        }

        // 3. Store everything in one save; if that fails, record by record.
        if (toStore.Count > 0)
        {
            _db.VettingDecisions.AddRange(toStore.Select(t => t.Entity));
            try
            {
                await _db.SaveChangesAsync(cancellationToken);
                foreach (var (index, entity) in toStore) results[index] = new VettingSyncResult(entity.Id, true);
            }
            catch (DbUpdateException ex)
            {
                _logger.LogWarning(ex, "Batch save failed, retrying {Count} decision(s) one by one.", toStore.Count);
                _db.ChangeTracker.Clear();
                foreach (var (index, entity) in toStore) results[index] = await StoreOneAsync(entity, officer, cancellationToken);
            }
        }

        return results!;
    }

    private async Task<VettingSyncResult> StoreOneAsync(VettingDecision entity, string officer, CancellationToken cancellationToken)
    {
        try
        {
            _db.VettingDecisions.Add(entity);
            await _db.SaveChangesAsync(cancellationToken);
            return new VettingSyncResult(entity.Id, true);
        }
        catch (DbUpdateException ex)
        {
            _db.ChangeTracker.Clear();

            // A concurrent retry may have stored this very id between our check and our insert.
            var stored = await _db.VettingDecisions.AsNoTracking()
                .Where(d => d.Id == entity.Id).Select(d => d.OfficerId).SingleOrDefaultAsync(cancellationToken);
            if (stored is not null)
            {
                return stored == officer
                    ? new VettingSyncResult(entity.Id, true, AlreadyReceived: true)
                    : Invalid(entity.Id, "This id belongs to another officer's decision.");
            }

            _logger.LogError(ex, "Could not store vetting decision {Id}.", entity.Id);
            return new VettingSyncResult(
                entity.Id, false, Error: "The server could not save this decision right now. Try again later.",
                ErrorCode: CboSyncErrorCodes.ServerError, Retryable: true);
        }
    }

    private static VettingSyncResult Invalid(string? id, string error) =>
        new(id, false, Error: error, ErrorCode: CboSyncErrorCodes.ValidationFailed, Retryable: false);

    private static VettingDecision ToEntity(VettingDecisionSyncItemDto d, string officer)
    {
        VettingDecisionSyncValidator.TryParseOutcome(d.Outcome, out var outcome); // already validated
        return new VettingDecision
        {
            Id = d.Id!,
            FoodspaceRecordId = d.FoodspaceRecordId!.Trim(),
            Outcome = outcome,
            Notes = string.IsNullOrWhiteSpace(d.Notes) ? null : d.Notes.Trim(),
            OfficerId = officer,
            DecisionTimestamp = d.DecisionTimestamp,
            CreatedAt = d.CreatedAt,
            UpdatedAt = d.UpdatedAt,
            // It has reached this backend. Where it stands with Foodspace is tracked by ForwardingStatus
            // (starts Pending; the forwarding loop picks it up).
            SyncStatus = SyncStatus.Synced,
        };
    }
}
