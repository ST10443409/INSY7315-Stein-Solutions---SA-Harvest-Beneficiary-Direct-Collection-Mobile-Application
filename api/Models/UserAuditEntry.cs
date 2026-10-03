namespace api.Models;

/// <summary>What was done to a user account. Wire value: UPPER_SNAKE_CASE (<c>PASSWORD_RESET</c>).</summary>
public enum UserAuditAction
{
    Created,
    RoleChanged,
    CboChanged,
    Deactivated,
    Reactivated,
    PasswordReset
}

/// <summary>
/// One line of the append-only trail of account changes ("user_audit" table, server-only): who did what to which account, and
/// when. Written in the same save as the change itself, so the two cannot disagree. It never holds a password, a hash or a token.
/// Design document B4: Admin actions must be logged for auditing; creating accounts and ending a person's access are the
/// actions an audit most needs to be able to answer for.
/// </summary>
public class UserAuditEntry
{
    public Guid Id { get; set; }

    public DateTimeOffset OccurredAt { get; set; }

    /// <summary>The Admin's username, or <c>system:bootstrap</c> for the first admin created from configuration.</summary>
    public required string Actor { get; set; }

    public UserAuditAction Action { get; set; }

    public Guid TargetUserId { get; set; }

    /// <summary>The target's username when this happened (a copy, so the trail still reads right if usernames ever change).</summary>
    public required string TargetUsername { get; set; }

    /// <summary>What changed, in words and values that are safe to show: <c>CBO_COLLECTION to VETTING</c>, a CBO id. Never secret.</summary>
    public string? Detail { get; set; }
}
