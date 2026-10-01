using api.Data;
using api.DTOs;
using api.Models;
using Microsoft.EntityFrameworkCore;

namespace api.Services;

public interface ICboCollectionIngestionService
{
    /// <summary>Validates and stores a batch. Always returns one result per submitted record, in the same order.</summary>
    Task<IReadOnlyList<CboCollectionSyncResult>> IngestAsync(
        IReadOnlyList<CboCollectionSyncItemDto> records, string? submittedBy, string? enforcedCboId = null, CancellationToken cancellationToken = default);
}

/// <summary>
/// Stores CBO collections sent by the Android app. Two different situations look alike and are handled differently:
///
/// 1. RETRY (#36), same client id sent again: the first attempt's response was probably lost. The id is the primary
///    key, so nothing is written and the record is reported as a success with AlreadyReceived = true ("already exists,
///    treat as success"). First write wins; a retry carrying different data does not overwrite it.
///
/// 2. DUPLICATE (#38), a DIFFERENT client id for a collection already received (same CBO, donor, date and delivery
///    note, see <see cref="CboCollectionDuplicateKey"/>): e.g. two collectors, or a device that lost its data. It is
///    reported as DUPLICATE_DETECTED (not a success, not a validation error), points at the original, and is stored
///    with DuplicateOfId set so an Admin can review it. The original is never touched or merged, and the duplicate
///    is not forwarded to Foodspace. Re-sending a stored duplicate gets the same DUPLICATE_DETECTED answer.
///
/// The database enforces rule 2 as well: a partial unique index allows only one original per duplicate key, so
/// two simultaneous submissions cannot both become originals.
///
/// Each record succeeds or fails on its own; one record never affects the others.
/// </summary>
public class CboCollectionIngestionService : ICboCollectionIngestionService
{
    private readonly AppDbContext _db;
    private readonly ILogger<CboCollectionIngestionService> _logger;

    public CboCollectionIngestionService(AppDbContext db, ILogger<CboCollectionIngestionService> logger)
    {
        _db = db;
        _logger = logger;
    }

    public async Task<IReadOnlyList<CboCollectionSyncResult>> IngestAsync(
        IReadOnlyList<CboCollectionSyncItemDto> records, string? submittedBy, string? enforcedCboId = null, CancellationToken cancellationToken = default)
    {
        // A collector's CBO comes from their account (the JWT), not from the request: whatever the device sent is replaced
        // before validation and before the duplicate key is computed, so records from a device that does not know its
        // CBO yet (or sends the wrong one) are still stored, and matched, under the right CBO.
        if (!string.IsNullOrWhiteSpace(enforcedCboId))
        {
            foreach (var record in records)
                if (record is not null) record.CboId = enforcedCboId;
        }

        var results = new CboCollectionSyncResult?[records.Count];
        var toStore = new List<(int Index, CboCollection Entity)>();

        // 1. Validate every record on its own.
        var validIds = new List<string>();
        for (var i = 0; i < records.Count; i++)
        {
            var errors = records[i] is null ? new List<string> { "Record is missing." } : CboCollectionSyncValidator.Validate(records[i]);
            if (errors.Count > 0)
            {
                results[i] = new CboCollectionSyncResult(
                    records[i]?.Id, false, Error: string.Join(" ", errors),
                    ErrorCode: CboSyncErrorCodes.ValidationFailed, Retryable: false);
            }
            else
            {
                validIds.Add(records[i].Id!);
            }
        }

        // 2. Ids already stored are retries (or re-sent duplicates).
        var existing = await _db.CboCollections.AsNoTracking()
            .Where(c => validIds.Contains(c.Id))
            .Select(c => new { c.Id, c.DuplicateOfId })
            .ToDictionaryAsync(c => c.Id, c => c.DuplicateOfId, cancellationToken);

        // 3. For the new ids, find which real-world collections already have an original.
        var keys = new Dictionary<int, string>();
        for (var i = 0; i < records.Count; i++)
        {
            if (results[i] is null && !existing.ContainsKey(records[i].Id!))
                keys[i] = CboCollectionDuplicateKey.For(records[i]);
        }
        var originalsByKey = keys.Count == 0
            ? new Dictionary<string, string>()
            : await _db.CboCollections.AsNoTracking()
                .Where(c => c.DuplicateOfId == null && c.DuplicateKey != null && keys.Values.Contains(c.DuplicateKey))
                .ToDictionaryAsync(c => c.DuplicateKey!, c => c.Id, cancellationToken);

        var seenInBatch = new HashSet<string>();
        for (var i = 0; i < records.Count; i++)
        {
            if (results[i] is not null) continue;
            var id = records[i].Id!;

            if (existing.TryGetValue(id, out var storedDuplicateOf))
            {
                results[i] = storedDuplicateOf is null
                    ? new CboCollectionSyncResult(id, true, AlreadyReceived: true)   // rule 1: retry
                    : DuplicateResult(id, storedDuplicateOf);                          // a stored duplicate sent again
                continue;
            }
            if (!seenInBatch.Add(id))
            {
                results[i] = new CboCollectionSyncResult(id, true, AlreadyReceived: true); // same id twice in one batch
                continue;
            }

            var key = keys[i];
            var entity = ToEntity(records[i], submittedBy, key);
            if (originalsByKey.TryGetValue(key, out var originalId))
            {
                entity.DuplicateOfId = originalId;                                     // rule 2: duplicate
                results[i] = DuplicateResult(id, originalId);
            }
            else
            {
                originalsByKey[key] = id; // later records in this batch with the same key are duplicates of this one
            }
            toStore.Add((i, entity));
        }

        // 4. Store everything (originals and flagged duplicates) in one save; if that fails, record by record.
        if (toStore.Count > 0)
        {
            _db.CboCollections.AddRange(toStore.Select(t => t.Entity));
            try
            {
                await _db.SaveChangesAsync(cancellationToken);
                foreach (var (index, entity) in toStore)
                    results[index] ??= new CboCollectionSyncResult(entity.Id, true);
            }
            catch (DbUpdateException ex)
            {
                _logger.LogWarning(ex, "Batch save failed, retrying {Count} record(s) one by one.", toStore.Count);
                _db.ChangeTracker.Clear();
                foreach (var (index, entity) in toStore)
                    results[index] = await StoreOneAsync(entity, cancellationToken);
            }
        }

        if (results.Any(r => r is { ErrorCode: CboSyncErrorCodes.DuplicateDetected }))
            _logger.LogInformation("Sync batch contained {Count} suspected duplicate(s), kept for review.",
                results.Count(r => r is { ErrorCode: CboSyncErrorCodes.DuplicateDetected }));

        return results!;
    }

    private static CboCollectionSyncResult DuplicateResult(string id, string originalId) => new(
        id, false,
        Error: $"A collection for this CBO, donor, date and delivery note was already received (record {originalId}). " +
               "This submission was kept for Admin review and was not merged. Do not resend it.",
        ErrorCode: CboSyncErrorCodes.DuplicateDetected,
        Retryable: false,
        DuplicateOfId: originalId);

    private async Task<CboCollectionSyncResult> StoreOneAsync(CboCollection entity, CancellationToken cancellationToken)
    {
        try
        {
            _db.CboCollections.Add(entity);
            await _db.SaveChangesAsync(cancellationToken);
            return entity.DuplicateOfId is { } original ? DuplicateResult(entity.Id, original) : new CboCollectionSyncResult(entity.Id, true);
        }
        catch (DbUpdateException ex)
        {
            _db.ChangeTracker.Clear();

            // A concurrent retry may have stored this very id between our check and our insert.
            var stored = await _db.CboCollections.AsNoTracking()
                .Where(c => c.Id == entity.Id).Select(c => new { c.DuplicateOfId }).SingleOrDefaultAsync(cancellationToken);
            if (stored is not null)
                return stored.DuplicateOfId is { } o ? DuplicateResult(entity.Id, o) : new CboCollectionSyncResult(entity.Id, true, AlreadyReceived: true);

            // The unique index fired: another request became the original for this key first. Store ours as its duplicate.
            if (entity.DuplicateOfId is null && entity.DuplicateKey is not null)
            {
                var originalId = await _db.CboCollections.AsNoTracking()
                    .Where(c => c.DuplicateKey == entity.DuplicateKey && c.DuplicateOfId == null)
                    .Select(c => c.Id).SingleOrDefaultAsync(cancellationToken);
                if (originalId is not null)
                {
                    entity.DuplicateOfId = originalId;
                    return await StoreOneAsync(entity, cancellationToken);
                }
            }

            _logger.LogError(ex, "Could not store CBO collection {Id}.", entity.Id);
            return new CboCollectionSyncResult(
                entity.Id, false, Error: "The server could not save this record right now. Try again later.",
                ErrorCode: CboSyncErrorCodes.ServerError, Retryable: true);
        }
    }

    private static CboCollection ToEntity(CboCollectionSyncItemDto r, string? submittedBy, string duplicateKey)
    {
        var id = r.Id!;
        return new CboCollection
        {
            Id = id,
            CboId = r.CboId!.Trim(),
            ArrivalTime = r.ArrivalTime!.Trim(),
            DepartureTime = string.IsNullOrWhiteSpace(r.DepartureTime) ? null : r.DepartureTime.Trim(),
            DonorName = r.DonorName!.Trim(),
            DonorSigned = r.DonorSigned,
            CboSigned = r.CboSigned,
            DeliveryNote = r.DeliveryNote?.Trim() ?? string.Empty,
            NoteAttached = r.NoteAttached,
            CollectNotes = r.CollectNotes?.Trim() ?? string.Empty,
            Shots = r.Shots!.ToList(),
            Latitude = r.Latitude,
            Longitude = r.Longitude,
            CreatedAt = r.CreatedAt,
            UpdatedAt = r.UpdatedAt,
            SubmittedBy = submittedBy,
            DuplicateKey = duplicateKey,
            // It has reached this backend. Where it stands with Foodspace is tracked by ForwardingStatus
            // (starts Pending; the forwarding loop picks up originals only).
            SyncStatus = SyncStatus.Synced,
            ProductLines = r.ProductLines!.Select(p => new ProductLine
            {
                Id = p.Id!,
                CollectionId = id,
                Category = p.Category!.Trim(),
                Kg = p.Kg!.Trim(),
                Notes = string.IsNullOrWhiteSpace(p.Notes) ? null : p.Notes.Trim(),
                CreatedAt = p.CreatedAt ?? r.CreatedAt,
                UpdatedAt = p.UpdatedAt ?? r.UpdatedAt,
                SyncStatus = SyncStatus.Synced,
            }).ToList(),
        };
    }
}
