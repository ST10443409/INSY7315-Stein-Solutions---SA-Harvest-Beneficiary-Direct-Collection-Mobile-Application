namespace api.Models;

/// <summary>
/// The record of one signature or photo whose bytes are in blob storage ("collection_attachments", the server's counterpart of the
/// device's table of the same name). The bytes are never in this table: <see cref="BlobName"/> points at them.
///
/// The row's id is the id the DEVICE gave the file, so a retried upload is recognised and is harmless (same rule as records:
/// first write wins). At most one file exists per collection, kind and slot.
/// </summary>
public class CollectionAttachment
{
    /// <summary>The device's attachment id, kept verbatim.</summary>
    public required string Id { get; set; }

    /// <summary>The collection it belongs to (real foreign key; a collection with files is never deleted out from under them).</summary>
    public required string CollectionId { get; set; }

    public AttachmentKind Kind { get; set; }

    /// <summary>Which numbered photo, for <see cref="AttachmentKind.Photo"/>; 0 for every other kind.</summary>
    public int Slot { get; set; }

    /// <summary>Chosen by the server (<c>{collectionId}/{attachmentId}</c>), never taken from the client.</summary>
    public required string BlobName { get; set; }

    /// <summary>What the bytes really are (<c>image/jpeg</c> or <c>image/png</c>), from the file's own header, not from what the client claimed.</summary>
    public required string ContentType { get; set; }

    public long SizeBytes { get; set; }

    /// <summary>SHA-256 of the bytes, lower-case hex: proof the file is the one that was received.</summary>
    public required string Sha256 { get; set; }

    /// <summary>The signed-in user who uploaded it (always the collection's submitter).</summary>
    public required string UploadedBy { get; set; }

    /// <summary>Set by the database (now()).</summary>
    public DateTimeOffset UploadedAt { get; set; }

    public CboCollection? Collection { get; set; }
}
