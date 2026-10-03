using System.ComponentModel.DataAnnotations;

namespace api.DTOs;

/// <summary>
/// Body of <c>POST /api/admin/users</c>. The role is the wire name (CBO_COLLECTION, VETTING, ADMIN, any case). The password is
/// the account's first one, chosen by the Admin and handed over out of band; it is checked against the password rules and only
/// its hash is stored. ToString leaves it out so it cannot reach a log through a stray interpolation.
/// </summary>
public class CreateUserRequest
{
    [Required, MaxLength(100)]
    public string Username { get; set; } = string.Empty;

    [Required, MaxLength(50)]
    public string Role { get; set; } = string.Empty;

    /// <summary>The CBO a CBO_COLLECTION user collects for; required for that role, refused for the others.</summary>
    [MaxLength(200)]
    public string? CboId { get; set; }

    [Required, MaxLength(200)]
    public string Password { get; set; } = string.Empty;

    public override string ToString() => $"CreateUserRequest(Username={Username}, Role={Role})";
}

/// <summary>
/// Body of <c>PATCH /api/admin/users/{id}</c>. Every field is optional and an absent one is left alone. Changing the role
/// and/or CBO, or deactivating or reactivating, ends the account's existing sessions.
/// </summary>
public class UpdateUserRequest
{
    [MaxLength(50)]
    public string? Role { get; set; }

    [MaxLength(200)]
    public string? CboId { get; set; }

    public bool? IsActive { get; set; }
}

/// <summary>Body of <c>POST /api/admin/users/{id}/reset-password</c>. Ends the account's existing sessions.</summary>
public class ResetPasswordRequest
{
    [Required, MaxLength(200)]
    public string NewPassword { get; set; } = string.Empty;

    public override string ToString() => "ResetPasswordRequest(NewPassword=<hidden>)";
}

/// <summary>An account as an Admin sees it. Never carries a password, a hash or the security stamp.</summary>
public record UserDto(string Id, string Username, string Role, string? CboId, bool IsActive, DateTimeOffset CreatedAt);

/// <summary>One line of an account's history. <c>Action</c> is UPPER_SNAKE_CASE (CREATED, ROLE_CHANGED, ...).</summary>
public record UserAuditEntryDto(DateTimeOffset At, string Actor, string Action, string? Detail);

/// <summary>An account with its most recent history, newest first.</summary>
public record UserDetailDto(UserDto User, IReadOnlyList<UserAuditEntryDto> History);

public record UserListResponse(IReadOnlyList<UserDto> Items, int Page, int PageSize, int TotalCount, bool HasMore);
