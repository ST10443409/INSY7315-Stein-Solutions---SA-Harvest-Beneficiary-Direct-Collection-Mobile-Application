using api.Data;
using api.Models;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;

namespace api.Tests;

/// <summary>
/// The seeded test accounts' password follows configuration (#54): a SEED_TEST_PASSWORD value was once committed, and
/// changing it must actually retire the old one on an existing development database.
/// </summary>
public class TestUserSeederTests
{
    private static ServiceProvider Services()
    {
        var services = new ServiceCollection();
        var dbName = Guid.NewGuid().ToString(); // one database shared by every scope
        services.AddDbContext<AppDbContext>(o => o.UseInMemoryDatabase(dbName));
        services.AddScoped<IPasswordHasher<AppUser>, PasswordHasher<AppUser>>();
        return services.BuildServiceProvider();
    }

    private static async Task<bool> PasswordWorks(IServiceProvider services, string username, string password)
    {
        using var scope = services.CreateScope();
        var user = await scope.ServiceProvider.GetRequiredService<AppDbContext>().Users.SingleAsync(u => u.Username == username);
        var hasher = scope.ServiceProvider.GetRequiredService<IPasswordHasher<AppUser>>();
        return hasher.VerifyHashedPassword(user, user.PasswordHash, password) != PasswordVerificationResult.Failed;
    }

    private static async Task Seed(IServiceProvider services, string password)
    {
        using var scope = services.CreateScope();
        await TestUserSeeder.SeedAsync(scope.ServiceProvider, password, NullLogger.Instance);
    }

    [Fact]
    public async Task ChangingTheConfiguredPassword_RetiresTheOldOne()
    {
        using var services = Services();
        await Seed(services, "old-leaked-test-value");

        await Seed(services, "new-test-value");

        foreach (var (username, _) in TestUserSeeder.TestUsers)
        {
            Assert.True(await PasswordWorks(services, username, "new-test-value"));
            Assert.False(await PasswordWorks(services, username, "old-leaked-test-value"));
        }
    }

    [Fact]
    public async Task SeedingAgain_WithTheSamePassword_LeavesTheAccountsAsTheyWere()
    {
        using var services = Services();
        await Seed(services, "same-test-value");
        string HashOf(string username)
        {
            using var scope = services.CreateScope();
            return scope.ServiceProvider.GetRequiredService<AppDbContext>().Users.Single(u => u.Username == username).PasswordHash;
        }
        var before = HashOf("admin_test_user");

        await Seed(services, "same-test-value");

        Assert.Equal(before, HashOf("admin_test_user"));
    }
}
