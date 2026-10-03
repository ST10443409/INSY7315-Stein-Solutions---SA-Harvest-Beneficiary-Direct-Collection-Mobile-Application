using System.Security.Claims;
using api.DTOs;
using api.Models;
using api.Services;
using Microsoft.AspNetCore.Mvc;

namespace api.Controllers;

// Account management: who may sign in, and as what. ADMIN only (the controller's [Authorize]). Without these there is no way to
// create a login in a deployed environment, because the test-user seeder is Development-only; the very first admin comes from
// the Bootstrap settings (AdminBootstrapper).
public partial class AdminController
{
    /// <summary>A create or reset body is a few hundred bytes; anything bigger is not one.</summary>
    public const long MaxAccountRequestBytes = 16 * 1024;

    /// <summary>
    /// The accounts, by username. Filters, all optional: <c>role</c> (CBO_COLLECTION, VETTING, ADMIN), <c>active</c> (true or
    /// false) and <c>search</c> (part of a username). Paged like the other lists. Never carries a password or hash.
    /// </summary>
    [HttpGet("users")]
    public async Task<ActionResult<ApiResponse<UserListResponse>>> ListUsers(
        [FromQuery] string? role = null, [FromQuery] bool? active = null, [FromQuery] string? search = null,
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
        if (problems.Count > 0) return ValidationFailed(problems);

        return Success(await _users.ListAsync(new UserListQuery(parsedRole, active, search, page, pageSize), cancellationToken));
    }

    /// <summary>One account with its most recent history (who created or changed it, and when), newest first.</summary>
    [HttpGet("users/{id}")]
    public async Task<IActionResult> GetUser(string id, CancellationToken cancellationToken)
    {
        if (!Guid.TryParse(id, out var userId)) return NoSuchUser();
        var result = await _users.GetAsync(userId, cancellationToken);
        return result.Value is { } detail ? Ok(ApiResponse.Ok(detail)) : UserRejected(result);
    }

    /// <summary>
    /// Creates an account (201). The password is the account's first one, chosen by the Admin and handed over out of band; it
    /// must satisfy the password rules and only its hash is stored. A collector needs a <c>cboId</c>; the other roles must not
    /// have one. 409 if the username is taken.
    /// </summary>
    [HttpPost("users")]
    [RequestSizeLimit(MaxAccountRequestBytes)]
    public async Task<IActionResult> CreateUser([FromBody] CreateUserRequest request, CancellationToken cancellationToken)
    {
        if (CurrentAdmin() is not { } admin) return Unidentified();

        var result = await _users.CreateAsync(admin, request, cancellationToken);
        return result.Value is { } user ? StatusCode(StatusCodes.Status201Created, ApiResponse.Ok(user)) : UserRejected(result);
    }

    /// <summary>
    /// Changes an account's role, CBO and/or active state; absent fields are left alone. Any change ends the account's existing
    /// sessions (the next request with an old token is a 401, so the person signs in again with their new rights, or not at all
    /// if deactivated). You cannot deactivate or re-role yourself, and the last active Admin cannot be removed: 409.
    /// </summary>
    [HttpPatch("users/{id}")]
    [RequestSizeLimit(MaxAccountRequestBytes)]
    public async Task<IActionResult> UpdateUser(string id, [FromBody] UpdateUserRequest request, CancellationToken cancellationToken)
    {
        if (!Guid.TryParse(id, out var userId)) return NoSuchUser();
        if (CurrentAdmin() is not { } admin) return Unidentified();

        var result = await _users.UpdateAsync(admin, userId, request, cancellationToken);
        return result.Value is { } user ? Ok(ApiResponse.Ok(user)) : UserRejected(result);
    }

    /// <summary>
    /// Sets a new password and signs the account out everywhere. For a forgotten password, or after a lost phone. The new
    /// password must satisfy the password rules; it is never stored in readable form or returned.
    /// </summary>
    [HttpPost("users/{id}/reset-password")]
    [RequestSizeLimit(MaxAccountRequestBytes)]
    public async Task<IActionResult> ResetUserPassword(string id, [FromBody] ResetPasswordRequest request, CancellationToken cancellationToken)
    {
        if (!Guid.TryParse(id, out var userId)) return NoSuchUser();
        if (CurrentAdmin() is not { } admin) return Unidentified();

        var result = await _users.ResetPasswordAsync(admin, userId, request.NewPassword, cancellationToken);
        return result.Value is { } user ? Ok(ApiResponse.Ok(user)) : UserRejected(result);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /// <summary>The Admin is the account that signed in (its id and name come from the token), never something the request says.</summary>
    private AdminActor? CurrentAdmin()
    {
        var name = User.Identity?.Name;
        if (string.IsNullOrWhiteSpace(name)) return null;
        Guid? id = Guid.TryParse(User.FindFirstValue("sub"), out var parsed) ? parsed : null;
        return new AdminActor(id, name);
    }

    private ObjectResult NoSuchUser() => Failure(StatusCodes.Status404NotFound, ApiErrorCodes.NotFound, "No such user.");

    private ObjectResult ValidationFailed(IDictionary<string, string[]> details) =>
        StatusCode(StatusCodes.Status400BadRequest,
            ApiResponse.Fail(ApiErrorCodes.ValidationFailed, "The request is not valid.", HttpContext.TraceIdentifier, details));

    private ObjectResult UserRejected<T>(UserResult<T> result) => result.Status switch
    {
        UserResultStatus.NotFound => Failure(StatusCodes.Status404NotFound, ApiErrorCodes.NotFound, result.Message!),
        UserResultStatus.Invalid => ValidationFailed(result.Details ?? new Dictionary<string, string[]>()),
        _ => Failure(StatusCodes.Status409Conflict, ApiErrorCodes.Conflict, result.Message!),
    };
}
