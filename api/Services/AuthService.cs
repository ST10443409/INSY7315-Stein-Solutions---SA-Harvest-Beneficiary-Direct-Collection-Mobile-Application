using api.Data;
using api.Models;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace api.Services;

public interface IAuthService
{
    /// <summary>Returns the user when the credentials are valid and the account is active, otherwise null.</summary>
    Task<AppUser?> AuthenticateAsync(string username, string password, CancellationToken cancellationToken = default);
}

public class AuthService : IAuthService
{
    // Verified against when the username doesn't exist, so an unknown user costs the same time as a
    // wrong password and response timing can't be used to discover which usernames exist.
    private static readonly Lazy<string> DummyHash = new(() =>
        new PasswordHasher<AppUser>().HashPassword(new AppUser { Username = "x", PasswordHash = "x" }, "not-a-real-password"));

    private readonly AppDbContext _db;
    private readonly IPasswordHasher<AppUser> _hasher;
    private readonly ILogger<AuthService> _logger;

    public AuthService(AppDbContext db, IPasswordHasher<AppUser> hasher, ILogger<AuthService> logger)
    {
        _db = db;
        _hasher = hasher;
        _logger = logger;
    }

    public async Task<AppUser?> AuthenticateAsync(string username, string password, CancellationToken cancellationToken = default)
    {
        var normalized = Normalize(username);
        var user = await _db.Users.SingleOrDefaultAsync(u => u.Username == normalized, cancellationToken);

        if (user is null)
        {
            _hasher.VerifyHashedPassword(new AppUser { Username = normalized, PasswordHash = DummyHash.Value }, DummyHash.Value, password);
            _logger.LogWarning("Failed login attempt for unknown user {Username}", normalized);
            return null;
        }

        var result = _hasher.VerifyHashedPassword(user, user.PasswordHash, password);
        if (result == PasswordVerificationResult.Failed || !user.IsActive)
        {
            _logger.LogWarning("Failed login attempt for user {Username}", normalized);
            return null;
        }

        if (result == PasswordVerificationResult.SuccessRehashNeeded)
        {
            // The hasher's parameters were strengthened since this hash was made: upgrade it transparently.
            user.PasswordHash = _hasher.HashPassword(user, password);
            await _db.SaveChangesAsync(cancellationToken);
        }

        return user;
    }

    public static string Normalize(string username) => username.Trim().ToLowerInvariant();
}
