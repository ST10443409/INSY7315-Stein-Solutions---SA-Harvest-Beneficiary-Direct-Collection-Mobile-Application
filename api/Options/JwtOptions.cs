using System.ComponentModel.DataAnnotations;

namespace api.Options;

/// <summary>
/// JWT settings, bound from the "Jwt" configuration section and validated at start-up, so a missing or
/// weak signing key stops the app immediately instead of failing on the first login.
/// The signing key is a secret: set it with `dotnet user-secrets` or the Jwt__SigningKey environment
/// variable. It must never be committed (see api/README.md).
/// </summary>
public class JwtOptions
{
    public const string SectionName = "Jwt";

    [Required]
    public string Issuer { get; set; } = string.Empty;

    [Required]
    public string Audience { get; set; } = string.Empty;

    [Required(ErrorMessage = "Jwt:SigningKey is not configured. Set it via user-secrets or the Jwt__SigningKey environment variable (see api/README.md).")]
    [MinLength(32, ErrorMessage = "Jwt:SigningKey must be at least 32 characters (256 bits).")]
    public string SigningKey { get; set; } = string.Empty;

    /// <summary>Kept short on purpose: the mobile app must handle 401-on-expiry by returning to login.</summary>
    [Range(1, 1440)]
    public int ExpiryMinutes { get; set; } = 60;
}
