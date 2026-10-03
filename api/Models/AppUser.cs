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

    /// <summary>
    /// The CBO this user collects for (matches <c>cbo_collections.cbo_id</c>). Set for CBO_COLLECTION users; null for
    /// Vetting and Admin users. It is carried in the JWT, and the sync endpoint stamps it on every record the user
    /// submits, so a collector can neither forget nor fake their CBO. No FK: like the collections, it must not
    /// depend on the CBO list having been pulled.
    /// </summary>
    public string? CboId { get; set; }

    /// <summary>Deactivated users cannot sign in; their record is kept for audit.</summary>
    public bool IsActive { get; set; } = true;

    public DateTimeOffset CreatedAt { get; set; }

    /// <summary>
    /// Copied into every token this user is given (the "stamp" claim) and compared with this value on every request. A new
    /// value is written whenever something that must end existing sessions changes: the account is deactivated or
    /// reactivated, the role or CBO changes, or the password is reset. A token carrying an old stamp is refused (401), so a
    /// lost phone or a leaver is locked out at once instead of when the 60-minute token runs out. Never sent to a client.
    /// </summary>
    public Guid SecurityStamp { get; set; } = Guid.NewGuid();
}
