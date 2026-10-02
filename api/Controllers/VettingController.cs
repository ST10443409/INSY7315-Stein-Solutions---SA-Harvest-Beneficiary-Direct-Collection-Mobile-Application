using api.DTOs;
using api.Models;
using api.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace api.Controllers;

/// <summary>Vetting (Form 2) endpoints. Serves /api/vetting.</summary>
[Authorize(Roles = $"{AppRoles.Vetting},{AppRoles.Admin}")]
public class VettingController : ApiControllerBase
{
    public const int DefaultPageSize = 50;
    public const int MaxPageSize = 100;

    private readonly IVettingRecordsService _records;
    private readonly IVettingDecisionIngestionService _decisions;

    public VettingController(IVettingRecordsService records, IVettingDecisionIngestionService decisions)
    {
        _records = records;
        _decisions = decisions;
    }

    /// <summary>
    /// The beneficiary records a vetting officer reviews, one page at a time, ordered by id. Only the fields the app's
    /// <c>FoodspaceBeneficiaryRecord</c> entity needs are returned. Ask for <c>page + 1</c> while <c>hasMore</c> is true.
    /// Optional <c>province</c> filter (case-insensitive). While Foodspace is unreachable the last cached list is served
    /// with <c>stale: true</c>; only when nothing has ever been cached is the answer <c>503 FOODSPACE_UNAVAILABLE</c>.
    /// </summary>
    [HttpGet("records")]
    public async Task<ActionResult<ApiResponse<VettingRecordsResponse>>> Records(
        [FromQuery] int page = 1, [FromQuery] int pageSize = DefaultPageSize, [FromQuery] string? province = null,
        CancellationToken cancellationToken = default)
    {
        var problems = new Dictionary<string, string[]>();
        if (page < 1) problems["page"] = new[] { "page must be 1 or more." };
        if (pageSize is < 1 or > MaxPageSize) problems["pageSize"] = new[] { $"pageSize must be between 1 and {MaxPageSize}." };
        if (problems.Count > 0)
        {
            return StatusCode(StatusCodes.Status400BadRequest,
                ApiResponse.Fail(ApiErrorCodes.ValidationFailed, "The paging parameters are not valid.", HttpContext.TraceIdentifier, problems));
        }

        var result = await _records.GetPageAsync(new VettingRecordsQuery(page, pageSize, province), cancellationToken);
        if (result.Page is not { } p)
        {
            return Failure(StatusCodes.Status503ServiceUnavailable, ApiErrorCodes.FoodspaceUnavailable,
                "The beneficiary records could not be fetched right now and none are stored yet. Try again when you are online.");
        }

        return Success(new VettingRecordsResponse(p.Items, p.Page, p.PageSize, p.TotalCount, p.HasMore, p.FetchedAt, p.Stale));
    }

    /// <summary>
    /// Receives a batch of vetting decisions from the Android app, the same shape as <c>POST /api/cbo-collection/sync</c>:
    /// replies 200 with one result per record, in order (<c>clientId</c>, <c>success</c>, and for failures <c>error</c>,
    /// <c>errorCode</c>, <c>retryable</c>), so the app updates each decision's sync status individually. Only a request
    /// that is unusable as a whole (no records, too many) is a 400. Idempotent on each decision's id. The officer recorded
    /// is the signed-in user, whatever the device sent. See <see cref="VettingDecisionIngestionService"/>.
    /// </summary>
    [HttpPost("sync")]
    [RequestSizeLimit(VettingDecisionSyncValidator.MaxRequestBytes)]
    public async Task<ActionResult<ApiResponse<VettingSyncResponse>>> Sync(
        [FromBody] VettingDecisionSyncRequest request, CancellationToken cancellationToken)
    {
        if (request.Records is null || request.Records.Count == 0)
            return Failure(StatusCodes.Status400BadRequest, ApiErrorCodes.ValidationFailed, "records must contain at least one record.");
        if (request.Records.Count > VettingDecisionSyncValidator.MaxBatchSize)
            return Failure(StatusCodes.Status400BadRequest, ApiErrorCodes.ValidationFailed,
                $"A batch can hold at most {VettingDecisionSyncValidator.MaxBatchSize} records.");

        // The officer is the account that signed in, not what the request body claims.
        var officer = User.Identity?.Name;
        if (string.IsNullOrWhiteSpace(officer))
            return Failure(StatusCodes.Status403Forbidden, ApiErrorCodes.Forbidden, "The signed-in user could not be identified.");

        var results = await _decisions.IngestAsync(request.Records, officer, cancellationToken);
        return Success(new VettingSyncResponse(results));
    }
}
