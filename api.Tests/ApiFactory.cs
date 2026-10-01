using api.Data;
using api.Models;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;

namespace api.Tests;

/// <summary>
/// Boots the real API in memory (real auth pipeline, real controllers) with an in-memory database
/// and test-only configuration, seeded with one user per role.
/// </summary>
public class ApiFactory : WebApplicationFactory<Program>
{
    // Test-only values; the real signing key is never in source.
    public const string SigningKey = "test-only-signing-key-0123456789-abcdefghij";
    public const string Issuer = "test-issuer";
    public const string Audience = "test-audience";
    public const string Password = "correct-test-password";

    public string? SigningKeyOverride { get; init; }

    private readonly string _dbName = Guid.NewGuid().ToString();

    /// <summary>Subclasses that replace the database with something unusable turn this off.</summary>
    protected virtual bool SeedUsers => true;

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseSetting("ConnectionStrings:Default", "Host=unused");
        builder.UseSetting("Jwt:Issuer", Issuer);
        builder.UseSetting("Jwt:Audience", Audience);
        builder.UseSetting("Jwt:SigningKey", SigningKeyOverride ?? SigningKey);
        builder.UseSetting("Jwt:ExpiryMinutes", "60");
        builder.UseSetting("Foodspace:ForwardingEnabled", "false"); // tests must not call out to Foodspace

        builder.ConfigureServices(services =>
        {
            // Swap Npgsql for an in-memory database.
            services.RemoveAll<DbContextOptions<AppDbContext>>();
            services.RemoveAll<AppDbContext>();
            foreach (var d in services.Where(d => d.ServiceType.IsGenericType
                         && d.ServiceType.GetGenericArguments().Contains(typeof(AppDbContext))).ToList())
                services.Remove(d);
            services.AddDbContext<AppDbContext>(o => o.UseInMemoryDatabase(_dbName));

            // Test-only endpoint (FaultController) for exercising the exception middleware.
            services.AddControllers().AddApplicationPart(typeof(FaultController).Assembly);
        });
    }

    protected override IHost CreateHost(IHostBuilder builder)
    {
        var host = base.CreateHost(builder);
        if (SeedUsers) Seed(host.Services);
        return host;
    }

    private static void Seed(IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        var hasher = scope.ServiceProvider.GetRequiredService<IPasswordHasher<AppUser>>();

        void Add(string username, UserRole role, bool active = true)
        {
            var user = new AppUser { Id = Guid.NewGuid(), Username = username, PasswordHash = "x", Role = role, IsActive = active };
            user.PasswordHash = hasher.HashPassword(user, Password);
            db.Users.Add(user);
        }

        Add("cbo_test_user", UserRole.CBO_COLLECTION);
        Add("vetting_test_user", UserRole.VETTING);
        Add("admin_test_user", UserRole.ADMIN);
        Add("disabled_user", UserRole.ADMIN, active: false);
        db.SaveChanges();
    }
}
