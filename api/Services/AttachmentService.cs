using System.Security.Cryptography;
using api.Data;
using api.DTOs;
using api.Models;
using api.Services.Attachments;
using Microsoft.EntityFrameworkCore;

namespace api.Services;

public enum AttachmentOutcome
{
    /// <summary>New file stored.</summary>
    Stored,

    /// <summary>The same file had already been received (a retry): nothing was changed.</summary>
    AlreadyReceived,

    /// <summary>No such collection, or it is not the caller's. Deliberately the same answer, so ids cannot be probed.</summary>
    NotFound,

    /// <summary>The id or the slot is already taken by a different file; resending cannot fix it.</summary>
    Conflict,

    /// <summary>Not a JPEG or PNG, or the bytes do not match what the request claimed.</summary>
    UnsupportedType,

    /// <summary>Over the size cap for its kind.</summary>
    TooLarge,

    /// <summary>Blob storage cannot be used right now; nothing was saved and the same request can be sent again.</summary>
    StorageUnavailable,
}

public sealed record AttachmentUploadResult(AttachmentOutcome Outcome, string? Message = null, long SizeBytes = 0);

/// <summary>A stored file with its bytes. The caller disposes <see cref="Content"/>'s stream.</summary>
public sealed record AttachmentRead(AttachmentInfo Info, AttachmentContent Content);

public interface IAttachmentService
{
    /// <summary>Validates and stores one file for one of <paramref name="uploader"/>'s own collections. Idempotent on <paramref name="attachmentId"/>.</summary>
    Task<AttachmentUploadResult> UploadAsync(
        string collectionId, string attachmentId, AttachmentKind kind, int slot, string? declaredMediaType, byte[] content,
        string uploader, CancellationToken cancellationToken);

    /// <summary>The files stored for a collection, or null when there is no such collection. (Admin.)</summary>
    Task<IReadOnlyList<AttachmentInfo>?> ListAsync(string collectionId, CancellationToken cancellationToken);

    /// <summary>One stored file with its bytes, or null when there is no such file. Throws <see cref="AttachmentStoreException"/> when storage is down. (Admin.)</summary>
    Task<AttachmentRead?> OpenAsync(string attachmentId, CancellationToken cancellationToken);
}

/// <summary>
/// Stores the signatures and photos of CBO collections (Form 1) in blob storage and keeps a row for each in the database.
///
/// The rules, in the order they are applied:
/// 1. The collection must exist and be the caller's own (the person who submitted it, the same author rule as the sync, #70);
///    anything else is "not found", so a stranger cannot tell which ids exist.
/// 2. The file must be small enough for its kind and really be a JPEG or PNG: the type comes from the bytes, and must agree with
///    the type the request declared.
/// 3. Retry-safe: the device's attachment id is the key. Sending the same file again is a success that changes nothing; a
///    different id for a slot that already has a file, or an id reused for a different slot, is a conflict.
/// 4. The blob name is chosen here (<c>{collectionId}/{attachmentId}</c>), never by the client.
/// The bytes are written first and the row second. A crash between the two leaves a blob with no row, which the retry simply
/// overwrites; the reverse (a row pointing at nothing) cannot happen.
/// </summary>
public class AttachmentService : IAttachmentService
{
    private readonly AppDbContext _db;
    private readonly IAttachmentStore _store;
    private readonly ILogger<AttachmentService> _logger;

    public AttachmentService(AppDbContext db, IAttachmentStore store, ILogger<AttachmentService> logger)
    {
        _db = db;
        _store = store;
        _logger = logger;
    }

    public async Task<AttachmentUploadResult> UploadAsync(
        string collectionId, string attachmentId, AttachmentKind kind, int slot, string? declaredMediaType, byte[] content,
        string uploader, CancellationToken cancellationToken)
    {
        // 1. Is it the caller's own collection?
        var submittedBy = await _db.CboCollections.AsNoTracking()
            .Where(c => c.Id == collectionId).Select(c => c.SubmittedBy).SingleOrDefaultAsync(cancellationToken);
        // No such collection and "someone else's" both leave submittedBy missing or different, and get the same answer.
        if (!string.Equals(submittedBy, uploader, StringComparison.OrdinalIgnoreCase))
            return new AttachmentUploadResult(AttachmentOutcome.NotFound, "No such collection.");

        // 2. Size and type.
        if (content.Length > AttachmentRules.MaxBytes(kind))
            return new AttachmentUploadResult(AttachmentOutcome.TooLarge, $"A {EnumWire.Of(kind)} can be at most {AttachmentRules.MaxBytes(kind) / 1024} KB.");

        var detected = AttachmentRules.DetectImageType(content);
        if (detected is null)
            return new AttachmentUploadResult(AttachmentOutcome.UnsupportedType, "The file is not a JPEG or PNG image.");
        if (!AttachmentRules.IsAllowedMediaType(declaredMediaType) || declaredMediaType != detected)
            return new AttachmentUploadResult(AttachmentOutcome.UnsupportedType, "The file's content does not match its declared type.");

        // 3. Retry, or conflict. ONE query answers both "is this id already stored?" and "is this slot taken?". Asking them in two
        // queries lets an identical upload commit in between: the second query then sees the slot taken (by this very file) and the
        // retry is wrongly called a conflict. Found by CI on a slower machine; pinned by AnUploadWhoseTwinCommitsBetweenItsChecks....
        var existing = await _db.CollectionAttachments.AsNoTracking()
            .Where(a => a.Id == attachmentId || (a.CollectionId == collectionId && a.Kind == kind && a.Slot == slot))
            .Select(a => new { a.Id, a.CollectionId, a.Kind, a.Slot, a.SizeBytes })
            .ToListAsync(cancellationToken);
        if (existing.Count > 0)
        {
            var sameId = existing.FirstOrDefault(a => a.Id == attachmentId);
            if (sameId is null)
                return new AttachmentUploadResult(AttachmentOutcome.Conflict, "This collection already has a file for that kind and slot.");
            return sameId.CollectionId == collectionId && sameId.Kind == kind && sameId.Slot == slot
                ? new AttachmentUploadResult(AttachmentOutcome.AlreadyReceived, SizeBytes: sameId.SizeBytes)
                : new AttachmentUploadResult(AttachmentOutcome.Conflict, "That attachment id is already used for a different file.");
        }

        // 4. Bytes first, then the row.
        var blobName = $"{collectionId}/{attachmentId}";
        try
        {
            using var stream = new MemoryStream(content, writable: false);
            await _store.PutAsync(blobName, stream, detected, cancellationToken);
        }
        catch (AttachmentStoreException ex)
        {
            _logger.LogError(ex, "Blob storage refused attachment {AttachmentId} of collection {CollectionId}.", attachmentId, collectionId);
            return new AttachmentUploadResult(AttachmentOutcome.StorageUnavailable, "File storage is not available right now. Try again later.");
        }

        _db.CollectionAttachments.Add(new CollectionAttachment
        {
            Id = attachmentId,
            CollectionId = collectionId,
            Kind = kind,
            Slot = slot,
            BlobName = blobName,
            ContentType = detected,
            SizeBytes = content.Length,
            Sha256 = Convert.ToHexStringLower(SHA256.HashData(content)),
            UploadedBy = uploader,
        });

        try
        {
            await _db.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateException ex)
        {
            _db.ChangeTracker.Clear();

            // A concurrent retry of the very same file stored its row first: the same blob name, so the same bytes are in place.
            var winner = await _db.CollectionAttachments.AsNoTracking()
                .Where(a => a.Id == attachmentId || (a.CollectionId == collectionId && a.Kind == kind && a.Slot == slot))
                .Select(a => new { a.Id, a.CollectionId, a.Kind, a.Slot, a.SizeBytes })
                .FirstOrDefaultAsync(cancellationToken);
            if (winner is not null)
            {
                if (winner.Id == attachmentId && winner.CollectionId == collectionId && winner.Kind == kind && winner.Slot == slot)
                    return new AttachmentUploadResult(AttachmentOutcome.AlreadyReceived, SizeBytes: winner.SizeBytes);

                // A different file took the slot first: ours is an orphan. Tidy up; failing to is harmless (nothing points at it).
                await TryDeleteAsync(blobName);
                return new AttachmentUploadResult(AttachmentOutcome.Conflict, "This collection already has a file for that kind and slot.");
            }

            _logger.LogError(ex, "Could not record attachment {AttachmentId} of collection {CollectionId}.", attachmentId, collectionId);
            await TryDeleteAsync(blobName);
            throw;
        }

        _logger.LogInformation("Stored {Kind} {AttachmentId} ({Bytes} bytes) for collection {CollectionId}.", EnumWire.Of(kind), attachmentId, content.Length, collectionId);
        return new AttachmentUploadResult(AttachmentOutcome.Stored, SizeBytes: content.Length);
    }

    public async Task<IReadOnlyList<AttachmentInfo>?> ListAsync(string collectionId, CancellationToken cancellationToken)
    {
        if (!await _db.CboCollections.AnyAsync(c => c.Id == collectionId, cancellationToken)) return null;

        var rows = await _db.CollectionAttachments.AsNoTracking()
            .Where(a => a.CollectionId == collectionId)
            .OrderBy(a => a.Kind).ThenBy(a => a.Slot)
            .ToListAsync(cancellationToken);
        return rows.Select(ToInfo).ToList();
    }

    public async Task<AttachmentRead?> OpenAsync(string attachmentId, CancellationToken cancellationToken)
    {
        var row = await _db.CollectionAttachments.AsNoTracking().SingleOrDefaultAsync(a => a.Id == attachmentId, cancellationToken);
        if (row is null) return null;

        var content = await _store.OpenReadAsync(row.BlobName, cancellationToken);
        if (content is null)
        {
            _logger.LogError("Attachment {AttachmentId} has a record but no stored file ({BlobName}).", attachmentId, row.BlobName);
            return null;
        }
        return new AttachmentRead(ToInfo(row), content);
    }

    private async Task TryDeleteAsync(string blobName)
    {
        try
        {
            await _store.DeleteAsync(blobName, CancellationToken.None);
        }
        catch (AttachmentStoreException ex)
        {
            _logger.LogWarning(ex, "Could not remove the unused file {BlobName}.", blobName);
        }
    }

    private static AttachmentInfo ToInfo(CollectionAttachment a) => new(
        a.Id, a.CollectionId, EnumWire.Of(a.Kind), a.Slot, a.ContentType, a.SizeBytes, a.Sha256, a.UploadedBy, a.UploadedAt);
}
