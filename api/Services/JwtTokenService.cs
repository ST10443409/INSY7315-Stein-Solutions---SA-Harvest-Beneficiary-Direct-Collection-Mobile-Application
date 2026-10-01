using System.Security.Claims;
using System.Text;
using api.Models;
using api.Options;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;

namespace api.Services;

public interface IJwtTokenService
{
    IssuedToken CreateToken(AppUser user);
}

public record IssuedToken(string Token, DateTimeOffset ExpiresAt);

public class JwtTokenService : IJwtTokenService
{
    /// <summary>The JWT claim carrying the role. The Android app and [Authorize(Roles = ...)] both rely on this name.</summary>
    public const string RoleClaim = "role";

    /// <summary>The JWT claim carrying the user's CBO (CBO_COLLECTION users only). The sync endpoint trusts this, not the request body.</summary>
    public const string CboIdClaim = "cbo_id";

    private readonly JwtOptions _options;
    private readonly TimeProvider _time;

    public JwtTokenService(IOptions<JwtOptions> options, TimeProvider time)
    {
        _options = options.Value;
        _time = time;
    }

    public IssuedToken CreateToken(AppUser user)
    {
        var now = _time.GetUtcNow();
        var expires = now.AddMinutes(_options.ExpiryMinutes);

        var claims = new List<Claim>
        {
            new(JwtRegisteredClaimNames.Sub, user.Id.ToString()),
            new(JwtRegisteredClaimNames.Name, user.Username),
            // Exactly CBO_COLLECTION | VETTING | ADMIN (the enum names), matching the Android UserRole.
            new(RoleClaim, user.Role.ToString()),
            new(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString()),
        };
        if (!string.IsNullOrWhiteSpace(user.CboId)) claims.Add(new Claim(CboIdClaim, user.CboId));

        var descriptor = new SecurityTokenDescriptor
        {
            Issuer = _options.Issuer,
            Audience = _options.Audience,
            IssuedAt = now.UtcDateTime,
            NotBefore = now.UtcDateTime,
            Expires = expires.UtcDateTime,
            Subject = new ClaimsIdentity(claims),
            SigningCredentials = new SigningCredentials(
                new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_options.SigningKey)),
                SecurityAlgorithms.HmacSha256),
        };

        return new IssuedToken(new JsonWebTokenHandler().CreateToken(descriptor), expires);
    }
}
