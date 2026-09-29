namespace api.Models;

/// <summary>
/// A person who can sign in ("users" table). Server-owned: unlike the other entities it has no
/// Room counterpart, because the device only ever holds the JWT, never the user record.
/// </summary>
public class AppUser
{
    public Guid Id { get; set; }

    /// <summary>Always stored lower-case, so lookups are case-insensitive and the unique index is meaningful.</summary>
    public required string Username { get; set; }

    /// <summary>ASP.NET Core PasswordHasher (PBKDF2, per-user salt) output. The password itself is never stored.</summary>
    public required string PasswordHash { get; set; }

    public UserRole Role { get; set; }

    /// <summary>Deactivated users cannot sign in; their record is kept for audit.</summary>
    public bool IsActive { get; set; } = true;

    public DateTimeOffset CreatedAt { get; set; }
}
