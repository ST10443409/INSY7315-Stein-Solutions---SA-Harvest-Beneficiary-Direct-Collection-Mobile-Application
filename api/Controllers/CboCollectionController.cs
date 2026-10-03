using System.Security.Claims;
using api.Data;
using api.DTOs;
using api.Models;
using api.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace api.Controllers;

/// <summary>CBO Collection (Form 1) endpoints. Serves /api/cbo-collection.</summary>
[Authorize(Roles = $"{AppRoles.CboCollection},{AppRoles.Admin}")]
public class CboCollectionController : ApiControllerBase
{
    private readonly ICboCollectionIngestionService _ingestion;
    private readonly IAttachmentService _attachments;

    public CboCollectionController(ICboCollectionIngestionService ingestion, IAttachmentService attachments)
    {
        _ingestion = ingestion;
        _attachments = attachments;
    }

    /// <summary>
    /// Receives a batch of collections from the Android app. Replies 200 with one result per record, in order
    /// (<c>clientId</c>, <c>success</c>, and for failures <c>error</c>, <c>errorCode</c>, <c>retryable</c>), so the app updates
    /// each record's sync status individually. Only a request that is unusable as a whole (no records, too many)
    /// is a 400. Idempotent on each record's id; see <see cref="CboCollectionIngestionService"/>.
    /// </summary>
    [HttpPost("sync")]
    [RequestSizeLimit(CboCollectionSyncValidator.MaxRequestBytes)]
    public async Task<ActionResult<ApiResponse<CboCollectionSyncResponse>>> Sync(
        [FromBody] CboCollectionSyncRequest request, CancellationToken cancellationToken)
    {
        if (request.Records is null || request.Records.Count == 0)
            return (ObjectResult)Failure(StatusCodes.Status400BadRequest, ApiErrorCodes.ValidationFailed, "records must contain at least one record.");
        if (request.Records.Count > CboCollectionSyncValidator.MaxBatchSize)
            return (ObjectResult)Failure(StatusCodes.Status400BadRequest, ApiErrorCodes.ValidationFailed,
                $"A batch can hold at most {CboCollectionSyncValidator.MaxBatchSize} records.");

        // A collector's CBO is the one on their account; the cboId in the request body is not trusted.
        var cboId = User.FindFirstValue(JwtTokenService.CboIdClaim);
        var results = await _ingestion.IngestAsync(request.Records, User.Identity?.Name, cboId, cancellationToken);
        return Success(new CboCollectionSyncResponse(results));
    }

    /// <summary>
    /// Stores one signature or photo of one of the caller's own collections in blob storage. The body is the raw image
    /// (<c>Content-Type: image/jpeg</c> or <c>image/png</c>), one file per call so a slow photo never holds up another; the query
    /// says what it is: <c>kind</c> (<c>DONOR_SIGNATURE</c>, <c>CBO_SIGNATURE</c>, <c>PHOTO</c> or <c>DELIVERY_NOTE</c>) and, for a
    /// photo, <c>slot</c>. <paramref name="attachmentId"/> is the id the device gave the file: sending the same file again is a
    /// success that changes nothing (<c>alreadyReceived</c>), which is what makes a retry after a lost answer safe.
    /// Answers: 200 stored; 400 bad ids/kind/slot/empty body; 404 not the caller's collection; 409 slot or id already used by a
    /// different file; 413 too large; 415 not a JPEG/PNG (judged from the bytes); 503 storage unavailable (send it again later).
    /// </summary>
    [HttpPut("{collectionId}/attachments/{attachmentId}")]
    [RequestSizeLimit(AttachmentRules.MaxRequestBytes)]
    public async Task<IActionResult> UploadAttachment(
        string collectionId, string attachmentId, [FromQuery] string? kind, [FromQuery] int slot = 0,
        CancellationToken cancellationToken = default)
    {
        var problems = new Dictionary<string, string[]>();
        if (!AttachmentRules.IsSafeId(collectionId)) problems["collectionId"] = new[] { "collectionId is not a valid id." };
        if (!AttachmentRules.IsSafeId(attachmentId)) problems["attachmentId"] = new[] { "attachmentId is not a valid id." };
        if (!AttachmentRules.TryParseKind(kind, out var parsedKind))
            problems["kind"] = new[] { "kind must be one of: DONOR_SIGNATURE, CBO_SIGNATURE, PHOTO, DELIVERY_NOTE." };
        else if (!AttachmentRules.SlotIsValid(parsedKind, slot))
            problems["slot"] = new[] { parsedKind == AttachmentKind.Photo ? $"slot must be between 0 and {AttachmentRules.MaxPhotoSlot}." : "slot must be 0 for this kind." };
        if (problems.Count > 0)
            return StatusCode(StatusCodes.Status400BadRequest,
                ApiResponse.Fail(ApiErrorCodes.ValidationFailed, "The upload request is not valid.", HttpContext.TraceIdentifier, problems));

        var uploader = User.Identity?.Name;
        if (string.IsNullOrEmpty(uploader)) return Failure(StatusCodes.Status401Unauthorized, ApiErrorCodes.Unauthorized, "The token names no user.");

        // Refuse a declared size over the cap before reading a byte of it; the read below enforces the same cap on what really arrives.
        var cap = AttachmentRules.MaxBytes(parsedKind);
        if (Request.ContentLength is { } declared && declared > cap) return TooLarge(parsedKind);

        var content = await ReadLimitedAsync(Request.Body, cap, cancellationToken);
        if (content is null) return TooLarge(parsedKind);
        if (content.Length == 0)
            return Failure(StatusCodes.Status400BadRequest, ApiErrorCodes.ValidationFailed, "The request body is empty: send the image bytes.");

        var result = await _attachments.UploadAsync(
            collectionId, attachmentId, parsedKind, slot, AttachmentRules.MediaTypeOf(Request.ContentType), content, uploader, cancellationToken);

        return result.Outcome switch
        {
            AttachmentOutcome.Stored => Ok(ApiResponse.Ok(new AttachmentUploadResponse(attachmentId, false, result.SizeBytes))),
            AttachmentOutcome.AlreadyReceived => Ok(ApiResponse.Ok(new AttachmentUploadResponse(attachmentId, true, result.SizeBytes))),
            AttachmentOutcome.NotFound => Failure(StatusCodes.Status404NotFound, ApiErrorCodes.NotFound, result.Message!),
            AttachmentOutcome.Conflict => Failure(StatusCodes.Status409Conflict, ApiErrorCodes.Conflict, result.Message!),
            AttachmentOutcome.TooLarge => Failure(StatusCodes.Status413PayloadTooLarge, ApiErrorCodes.PayloadTooLarge, result.Message!),
            AttachmentOutcome.UnsupportedType => Failure(StatusCodes.Status415UnsupportedMediaType, ApiErrorCodes.UnsupportedMediaType, result.Message!),
            _ => Failure(StatusCodes.Status503ServiceUnavailable, ApiErrorCodes.Unhealthy, result.Message!),
        };
    }

    private ObjectResult TooLarge(AttachmentKind kind) => Failure(
        StatusCodes.Status413PayloadTooLarge, ApiErrorCodes.PayloadTooLarge,
        $"A {EnumWire.Of(kind)} can be at most {AttachmentRules.MaxBytes(kind) / 1024} KB.");

    /// <summary>Reads at most <paramref name="max"/> bytes; null when the body is longer (decompression and a missing Content-Length cannot get past the cap).</summary>
    private static async Task<byte[]?> ReadLimitedAsync(Stream body, long max, CancellationToken cancellationToken)
    {
        using var buffer = new MemoryStream();
        var chunk = new byte[16 * 1024];
        int read;
        while ((read = await body.ReadAsync(chunk, cancellationToken)) > 0)
        {
            if (buffer.Length + read > max) return null;
            buffer.Write(chunk, 0, read);
        }
        return buffer.ToArray();
    }
}
