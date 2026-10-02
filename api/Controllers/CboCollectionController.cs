using System.Security.Claims;
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

    public CboCollectionController(ICboCollectionIngestionService ingestion) => _ingestion = ingestion;

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
}
