namespace api.DTOs;

/// <summary>The answer to a successful upload. <see cref="AlreadyReceived"/> is true when this exact file had arrived before (a retry).</summary>
public record AttachmentUploadResponse(string AttachmentId, bool AlreadyReceived, long SizeBytes);

/// <summary>What an Admin sees about a stored file. No blob address: the bytes are read only through the API.</summary>
public record AttachmentInfo(
    string Id, string CollectionId, string Kind, int Slot, string ContentType, long SizeBytes, string Sha256,
    string UploadedBy, DateTimeOffset UploadedAt);

public record AttachmentListResponse(string CollectionId, IReadOnlyList<AttachmentInfo> Attachments);
