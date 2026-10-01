using api.Data;
using api.DTOs;
using api.Models;
using api.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace api.Controllers;

/// <summary>Admin oversight endpoints (#49 to #51). Serves /api/admin. ADMIN only.</summary>
[Authorize(Roles = AppRoles.Admin)]
public class AdminController : ApiControllerBase
{
    public const int DefaultPageSize = 50;
    public const int MaxPageSize = 100;

    private readonly IAdminSyncStatusService _syncStatus;
    private readonly IAdminSyncResolutionService _resolution;
    private readonly IAdminUserActivityService _activity;
    private readonly TimeProvider _time;

    /// <summary>With no dates, activity covers this many South African days up to and including today.</summary>
    public const int DefaultActivityDays = 7;

    public AdminController(
        IAdminSyncStatusService syncStatus, IAdminSyncResolutionService resolution, IAdminUserActivityService activity, TimeProvider time)
    {
        _syncStatus = syncStatus;
        _resolution = resolution;
        _activity = activity;
        _time = time;
    }

    /// <summary>
    /// Who submitted or vetted what, and when (#51): Form 1 collections and Form 2 decisions, newest first, each with the
    /// acting user, their role, the time and a record reference. Read-only. Filters, all optional:
    /// <c>user</c> (username), <c>role</c> (<c>CBO_COLLECTION</c>, <c>VETTING</c>, <c>ADMIN</c>), and <c>from</c> / <c>to</c>
    /// (<c>yyyy-MM-dd</c>, South African days, <c>to</c> inclusive). With neither date, only the last
    /// <see cref="DefaultActivityDays"/> days are returned (the answer's <c>from</c> / <c>to</c> show what was applied), so the
    /// whole history is never returned by default; paged like the other lists.
    /// </summary>
    [HttpGet("user-activity")]
    public async Task<ActionResult<ApiResponse<UserActivityResponse>>> UserActivity(
        [FromQuery] string? user = null, [FromQuery] string? role = null, [FromQuery] string? from = null, [FromQuery] string? to = null,
        [FromQuery] int page = 1, [FromQuery] int pageSize = DefaultPageSize, CancellationToken cancellationToken = default)
    {
        var problems = new Dictionary<string, string[]>();
        if (page < 1) problems["page"] = new[] { "page must be 1 or more." };
        if (pageSize is < 1 or > MaxPageSize) problems["pageSize"] = new[] { $"pageSize must be between 1 and {MaxPageSize}." };

        UserRole? parsedRole = null;
        if (!string.IsNullOrWhiteSpace(role))
        {
            if (Enum.TryParse<UserRole>(role.Trim(), ignoreCase: true, out var r) && Enum.IsDefined(r)) parsedRole = r;
            else problems["role"] = new[] { $"role must be one of: {string.Join(", ", Enum.GetNames<UserRole>())}." };
        }

        var parsedFrom = ParseDate(from, "from", problems);
        var parsedTo = ParseDate(to, "to", problems);
        if (parsedFrom is { } f && parsedTo is { } t && f > t) problems["to"] = new[] { "to must be on or after from." };

        if (problems.Count > 0)
        {
            return StatusCode(StatusCodes.Status400BadRequest,
                ApiResponse.Fail(ApiErrorCodes.ValidationFailed, "The request is not valid.", HttpContext.TraceIdentifier, problems));
        }

        if (parsedFrom is null && parsedTo is null)
        {
            var today = DateOnly.FromDateTime(_time.GetUtcNow().ToOffset(SyncRecordInfo.SouthAfrica).DateTime);
            parsedFrom = today.AddDays(-(DefaultActivityDays - 1));
            parsedTo = today;
        }

        return Success(await _activity.GetAsync(new UserActivityQuery(user, parsedRole, parsedFrom, parsedTo, page, pageSize), cancellationToken));
    }

    private static DateOnly? ParseDate(string? raw, string name, Dictionary<string, string[]> problems)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        // Years 2000 to 2100: far inside what a date can hold, so the end-of-day arithmetic can never overflow.
        if (DateOnly.TryParseExact(raw.Trim(), "yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.None, out var date)
            && date.Year is >= 2000 and <= 2100)
            return date;
        problems[name] = new[] { $"{name} must be a date like 2026-09-20." };
        return null;
    }

    /// <summary>
    /// How many Form 1 collections and Form 2 decisions are waiting, retrying, forwarded, need attention, are held suspected
    /// duplicates, were superseded or were dismissed. Counts only; the records themselves are not returned.
    /// </summary>
    [HttpGet("sync-status")]
    public async Task<ActionResult<ApiResponse<AdminSyncStatusResponse>>> SyncStatus(CancellationToken cancellationToken) =>
        Success(await _syncStatus.GetAsync(cancellationToken));

    /// <summary>
    /// The records that need an Admin (#50): rejected by Foodspace, retries used up, or held as suspected duplicates, oldest
    /// first, with the error and attempt count. Optional <c>form</c> filter: <c>CBO_COLLECTION</c> or <c>VETTING_DECISION</c>.
    /// </summary>
    [HttpGet("sync-status/attention")]
    public async Task<ActionResult<ApiResponse<SyncAttentionResponse>>> Attention(
        [FromQuery] string? form = null, [FromQuery] int page = 1, [FromQuery] int pageSize = DefaultPageSize,
        CancellationToken cancellationToken = default)
    {
        var problems = new Dictionary<string, string[]>();
        if (page < 1) problems["page"] = new[] { "page must be 1 or more." };
        if (pageSize is < 1 or > MaxPageSize) problems["pageSize"] = new[] { $"pageSize must be between 1 and {MaxPageSize}." };
        if (!TryParseForm(form, out var parsedForm)) problems["form"] = new[] { FormProblem };
        if (problems.Count > 0)
        {
            return StatusCode(StatusCodes.Status400BadRequest,
                ApiResponse.Fail(ApiErrorCodes.ValidationFailed, "The request is not valid.", HttpContext.TraceIdentifier, problems));
        }

        return Success(await _resolution.ListAttentionAsync(parsedForm, page, pageSize, cancellationToken));
    }

    /// <summary>
    /// One record with its error detail, what an Admin may do with it (<c>canRetry</c>, <c>canDismiss</c>) and what has
    /// already been done to it. Only needed to say which kind of record when a collection and a decision share an id.
    /// </summary>
    [HttpGet("sync-status/{id}")]
    public async Task<ActionResult<ApiResponse<SyncRecordDetail>>> SyncRecord(
        string id, [FromQuery] string? form = null, CancellationToken cancellationToken = default)
    {
        if (!TryParseForm(form, out var parsedForm)) return BadForm();

        var result = await _resolution.GetAsync(id, parsedForm, cancellationToken);
        return result.Value is { } detail ? Success(detail) : Rejected(result);
    }

    /// <summary>
    /// Sends the record to Foodspace now and returns the outcome (<c>record.state</c>: <c>FORWARDED</c> if it worked,
    /// <c>RETRYING</c> if Foodspace is still failing and the server will keep trying, <c>NEEDS_ATTENTION</c> if it was
    /// rejected again). Starts the record's attempt count again, and on a held suspected duplicate means "this is a real
    /// collection, send it". The Admin and the time are recorded. 409 for a record Foodspace already has or that a newer
    /// decision replaced.
    /// </summary>
    [HttpPost("sync-status/{id}/retry")]
    public async Task<ActionResult<ApiResponse<ResolutionResponse>>> Retry(
        string id, [FromQuery] string? form = null, CancellationToken cancellationToken = default)
    {
        if (!TryParseForm(form, out var parsedForm)) return BadForm();
        if (Admin() is not { } admin) return Unidentified();

        var result = await _resolution.RetryAsync(id, parsedForm, admin, cancellationToken);
        return result.Value is { } response ? Success(response) : Rejected(result);
    }

    /// <summary>
    /// Marks a record that needs attention, or a held suspected duplicate, as never to be sent. A <c>reason</c> is required
    /// and is kept with the Admin and the time. 409 for any other record.
    /// </summary>
    [HttpPost("sync-status/{id}/dismiss")]
    public async Task<ActionResult<ApiResponse<ResolutionResponse>>> Dismiss(
        string id, [FromBody] DismissRequest request, [FromQuery] string? form = null, CancellationToken cancellationToken = default)
    {
        if (!TryParseForm(form, out var parsedForm)) return BadForm();
        if (Admin() is not { } admin) return Unidentified();

        var result = await _resolution.DismissAsync(id, parsedForm, request.Reason, admin, cancellationToken);
        return result.Value is { } response ? Success(response) : Rejected(result);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private const string FormProblem = "form must be CBO_COLLECTION or VETTING_DECISION.";

    /// <summary>The Admin is the account that signed in, never something the request says.</summary>
    private string? Admin() => string.IsNullOrWhiteSpace(User.Identity?.Name) ? null : User.Identity.Name;

    private ObjectResult Unidentified() =>
        Failure(StatusCodes.Status403Forbidden, ApiErrorCodes.Forbidden, "The signed-in user could not be identified.");

    private ObjectResult BadForm() =>
        Failure(StatusCodes.Status400BadRequest, ApiErrorCodes.ValidationFailed, FormProblem);

    /// <summary>Null/empty means "either"; otherwise the wire value, in any case.</summary>
    private static bool TryParseForm(string? raw, out SyncForm? form)
    {
        form = null;
        if (string.IsNullOrWhiteSpace(raw)) return true;
        foreach (var candidate in Enum.GetValues<SyncForm>())
        {
            if (!string.Equals(EnumWire.Of(candidate), raw.Trim(), StringComparison.OrdinalIgnoreCase)) continue;
            form = candidate;
            return true;
        }
        return false;
    }

    private ObjectResult Rejected<T>(ResolutionResult<T> result) => result.Status switch
    {
        ResolutionStatus.NotFound => Failure(StatusCodes.Status404NotFound, ApiErrorCodes.NotFound, result.Message!),
        ResolutionStatus.Invalid => Failure(StatusCodes.Status400BadRequest, ApiErrorCodes.ValidationFailed, result.Message!),
        _ => Failure(StatusCodes.Status409Conflict, ApiErrorCodes.Conflict, result.Message!) // not allowed in this state, or ambiguous
    };
}
