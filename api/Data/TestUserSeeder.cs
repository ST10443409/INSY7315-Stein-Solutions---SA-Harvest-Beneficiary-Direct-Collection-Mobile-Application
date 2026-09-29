using api.Models;
using api.Services;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace api.Data;

/// <summary>
/// Creates one known test user per role so the Android login (#27), RBAC checks (#52) and UAT (#60) have
/// credentials without a registration flow. Opt-in only: it runs when Seed:Enabled is true (set in
/// compose.yaml for local dev), never by default. The shared password comes from configuration
/// (Seed:TestUserPassword / SEED_TEST_PASSWORD in .env), so no password literal lives in source.
/// Idempotent: existing users are left untouched.
/// </summary>
public static class TestUserSeeder
{
    public static readonly (string Username, UserRole Role)[] TestUsers =
    {
        ("cbo_test_user", UserRole.CBO_COLLECTION),
        ("vetting_test_user", UserRole.VETTING),
        ("admin_test_user", UserRole.ADMIN),
    };

    public static async Task SeedAsync(IServiceProvider services, string? password, ILogger logger)
    {
        if (string.IsNullOrWhiteSpace(password))
        {
            logger.LogWarning("Seed:Enabled is true but Seed:TestUserPassword is not set; no test users were created.");
            return;
        }

        var db = services.GetRequiredService<AppDbContext>();
        var hasher = services.GetRequiredService<IPasswordHasher<AppUser>>();

        foreach (var (username, role) in TestUsers)
        {
            if (await db.Users.AnyAsync(u => u.Username == username))
                continue;

            var user = new AppUser { Username = AuthService.Normalize(username), PasswordHash = "pending", Role = role };
            user.PasswordHash = hasher.HashPassword(user, password);
            db.Users.Add(user);
            logger.LogWarning("Seeded TEST user {Username} with role {Role}. Development only.", username, role);
        }

        await db.SaveChangesAsync();
    }
}
