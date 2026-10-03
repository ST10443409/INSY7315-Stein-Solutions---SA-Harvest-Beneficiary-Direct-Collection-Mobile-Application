using api.DTOs;
using api.Services;
using api.Services.Attachments;
using Microsoft.AspNetCore.Mvc;

namespace api.Controllers;

// Oversight of the signatures and photos collectors uploaded. ADMIN only (the controller's [Authorize]). Read-only: nothing here
// can change or delete a file. The bytes are only ever handed out through the API (never as a blob address or link), so the same
// authentication and role check that guards everything else guards them.
public partial class AdminController
{
    /// <summary>The files stored for a collection (kind, slot, type, size, SHA-256, who uploaded, when). 404 when there is no such collection.</summary>
    [HttpGet("collections/{collectionId}/attachments")]
    public async Task<IActionResult> ListAttachments(string collectionId, CancellationToken cancellationToken)
    {
        if (!AttachmentRules.IsSafeId(collectionId)) return Failure(StatusCodes.Status404NotFound, ApiErrorCodes.NotFound, "No such collection.");
        var list = await _attachments.ListAsync(collectionId, cancellationToken);
        return list is null
            ? Failure(StatusCodes.Status404NotFound, ApiErrorCodes.NotFound, "No such collection.")
            : Ok(ApiResponse.Ok(new AttachmentListResponse(collectionId, list)));
    }

    /// <summary>
    /// The picture itself. The type is the one detected when it was uploaded; the response is never cached, never sniffed into
    /// anything else, and carries a policy that stops a browser running anything from it.
    /// </summary>
    [HttpGet("attachments/{id}")]
    public async Task<IActionResult> GetAttachment(string id, CancellationToken cancellationToken)
    {
        if (!AttachmentRules.IsSafeId(id)) return Failure(StatusCodes.Status404NotFound, ApiErrorCodes.NotFound, "No such attachment.");

        AttachmentRead? read;
        try
        {
            read = await _attachments.OpenAsync(id, cancellationToken);
        }
        catch (AttachmentStoreException)
        {
            return Failure(StatusCodes.Status503ServiceUnavailable, ApiErrorCodes.Unhealthy, "File storage is not available right now. Try again later.");
        }
        if (read is null) return Failure(StatusCodes.Status404NotFound, ApiErrorCodes.NotFound, "No such attachment.");

        Response.Headers.CacheControl = "private, no-store";
        Response.Headers.XContentTypeOptions = "nosniff";
        Response.Headers.ContentSecurityPolicy = "default-src 'none'; sandbox";
        return File(read.Content.Content, read.Info.ContentType); // disposed by the framework when the response ends
    }
}
