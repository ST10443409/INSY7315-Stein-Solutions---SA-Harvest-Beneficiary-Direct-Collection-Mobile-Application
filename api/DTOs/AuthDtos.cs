using System.ComponentModel.DataAnnotations;

namespace api.DTOs;

public class LoginRequest
{
    [Required, MaxLength(100)]
    public string Username { get; set; } = string.Empty;

    [Required, MaxLength(200)]
    public string Password { get; set; } = string.Empty;
}

/// <summary>
/// Response of POST /api/auth/login. "token" and "role" are what the Android LoginResponse reads.
/// "role" is one of CBO_COLLECTION, VETTING, ADMIN and equals the role claim inside the token.
/// "cboId" is the user's CBO (CBO_COLLECTION users only, otherwise null) and equals the cbo_id claim.
/// </summary>
public record LoginResponse(string Token, string Role, DateTimeOffset ExpiresAt, string? CboId = null);

public record CurrentUserResponse(string Id, string Username, string Role);
