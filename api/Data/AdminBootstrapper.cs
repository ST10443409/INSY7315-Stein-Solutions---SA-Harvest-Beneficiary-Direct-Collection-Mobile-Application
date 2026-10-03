using api.Models;
using api.Options;
using api.Services;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace api.Data;

/// <summary>
/// Creates the first administrator from configuration (see <see cref="BootstrapOptions"/>), so a fresh deployment has someone who
/// can sign in and create everyone else through the Admin endpoints. Unlike the test-user seeder it is meant for real
/// environments, which is why it is so cautious:
///   - it runs only when the Bootstrap settings are present, and does nothing once ANY active admin exists;
///   - it never touches an existing account (no password reset, no promotion): a username that is already taken stops the
///     start-up with an explanation instead;
///   - a half-set or unacceptable configuration stops the start-up too, so a typo cannot leave the system with no admin and no
///     message. The error never contains the password.
/// Call it after migrations have run.
/// </summary>
public static class AdminBootstrapper
{
    /// <summary>The actor named in the audit trail for the account this creates.</summary>
    public const string Actor = "system:bootstrap";

    public static async Task RunAsync(IServiceProvider services, BootstrapOptions options, ILogger logger, CancellationToken cancellationToken = default)
    {
        if (!options.IsConfigured) return;

        if (string.IsNullOrWhiteSpace(options.AdminUsername) || string.IsNullOrEmpty(options.AdminPassword))
        {
            throw new InvalidOperationException(
                "Bootstrap is half configured: set both Bootstrap:AdminUsername and Bootstrap:AdminPassword (or neither).");
        }

        var db = services.GetRequiredService<AppDbContext>();

        if (await db.Users.AnyAsync(u => u.Role == UserRole.ADMIN && u.IsActive, cancellationToken))
        {
            logger.LogInformation(
                "Bootstrap:AdminUsername is set but an active admin already exists, so nothing was created. " +
                "Remove the Bootstrap settings (above all the password): they have done their job.");
            return;
        }

        var username = AccountRules.NormalizeUsername(options.AdminUsername);
        var problems = new[] { AccountRules.UsernameProblem(username), AccountRules.PasswordProblem(username, options.AdminPassword) }
            .Where(p => p is not null).ToList();
        if (problems.Count > 0)
            throw new InvalidOperationException("Bootstrap:AdminUsername / Bootstrap:AdminPassword are not acceptable: " + string.Join(" ", problems));

        if (await db.Users.AnyAsync(u => u.Username == username, cancellationToken))
        {
            throw new InvalidOperationException(
                $"Bootstrap:AdminUsername '{username}' already exists but is not an active admin, and the bootstrap never changes an existing account. " +
                "Choose another username, or reactivate that account directly in the database.");
        }

        var hasher = services.GetRequiredService<IPasswordHasher<AppUser>>();
        var time = services.GetRequiredService<TimeProvider>();
        var user = new AppUser { Id = Guid.NewGuid(), Username = username, PasswordHash = "pending", Role = UserRole.ADMIN, CreatedAt = time.GetUtcNow() };
        user.PasswordHash = hasher.HashPassword(user, options.AdminPassword);
        db.Users.Add(user);
        db.UserAudit.Add(new UserAuditEntry
        {
            Id = Guid.NewGuid(),
            OccurredAt = time.GetUtcNow(),
            Actor = Actor,
            Action = UserAuditAction.Created,
            TargetUserId = user.Id,
            TargetUsername = user.Username,
            Detail = "role ADMIN, first administrator from configuration",
        });
        await db.SaveChangesAsync(cancellationToken);

        logger.LogWarning(
            "Created the first administrator '{Username}' from the Bootstrap settings. Sign in, then REMOVE Bootstrap:AdminPassword " +
            "from the configuration: it is not needed again and a secret that is no longer needed should not stay around.", username);
    }
}
