using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using api.Data;
using api.Models;
using api.Options;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;

namespace api.Tests;

/// <summary>
/// The first administrator, created from configuration so a fresh deployment has someone who can sign in (see
/// <see cref="AdminBootstrapper"/>). It is cautious on purpose, and these tests pin every caution down: it never changes an
/// existing account, does nothing once an admin exists, and stops the start-up on a configuration it cannot honour without ever
/// printing the password.
/// </summary>
public class AdminBootstrapperTests
{
    internal const string Password = "first-admin-passphrase-1";

    private static ServiceProvider NewServices()
    {
        var services = new ServiceCollection();
        services.AddDbContext<AppDbContext>(o => o.UseInMemoryDatabase(Guid.NewGuid().ToString()));
        services.AddSingleton<IPasswordHasher<AppUser>, PasswordHasher<AppUser>>();
        services.AddSingleton(TimeProvider.System);
        return services.BuildServiceProvider();
    }

    private static BootstrapOptions Options(string? username = "first.admin", string? password = Password) =>
        new() { AdminUsername = username, AdminPassword = password };

    private static Task RunAsync(ServiceProvider sp, BootstrapOptions options) =>
        AdminBootstrapper.RunAsync(sp, options, NullLogger.Instance);

    [Fact]
    public async Task WithNothingConfigured_NothingHappens()
    {
        await using var sp = NewServices();

        await RunAsync(sp, new BootstrapOptions());

        Assert.Empty(sp.GetRequiredService<AppDbContext>().Users);
    }

    [Fact]
    public async Task OnAnEmptySystem_ItCreatesTheFirstAdmin_WhoCanSignIn_WithAnAuditEntry()
    {
        await using var sp = NewServices();

        await RunAsync(sp, Options("  First.Admin "));

        var db = sp.GetRequiredService<AppDbContext>();
        var user = await db.Users.SingleAsync();
        Assert.Equal("first.admin", user.Username);
        Assert.Equal(UserRole.ADMIN, user.Role);
        Assert.True(user.IsActive);
        Assert.Null(user.CboId);
        Assert.NotEqual(Password, user.PasswordHash); // only the hash is stored
        Assert.NotEqual(PasswordVerificationResult.Failed, sp.GetRequiredService<IPasswordHasher<AppUser>>().VerifyHashedPassword(user, user.PasswordHash, Password));

        var audit = await db.UserAudit.SingleAsync();
        Assert.Equal(AdminBootstrapper.Actor, audit.Actor);
        Assert.Equal(UserAuditAction.Created, audit.Action);
        Assert.Equal(user.Id, audit.TargetUserId);
        Assert.DoesNotContain(Password, audit.Detail ?? "");
    }

    [Fact]
    public async Task RunningItAgain_ChangesNothing()
    {
        await using var sp = NewServices();
        await RunAsync(sp, Options());
        var db = sp.GetRequiredService<AppDbContext>();
        var before = (await db.Users.SingleAsync()).PasswordHash;

        await RunAsync(sp, Options(password: "a-different-passphrase-2")); // even a different password: it never resets

        Assert.Equal(before, (await db.Users.SingleAsync()).PasswordHash);
        Assert.Equal(1, await db.UserAudit.CountAsync());
    }

    [Fact]
    public async Task WhenAnActiveAdminAlreadyExists_NothingIsCreated()
    {
        await using var sp = NewServices();
        var db = sp.GetRequiredService<AppDbContext>();
        db.Users.Add(new AppUser { Id = Guid.NewGuid(), Username = "existing.admin", PasswordHash = "x", Role = UserRole.ADMIN });
        await db.SaveChangesAsync();

        await RunAsync(sp, Options("someone.else"));

        Assert.Equal("existing.admin", (await db.Users.SingleAsync()).Username);
    }

    [Fact]
    public async Task AnInactiveAdmin_DoesNotCountAsAnAdmin_ButItsUsernameIsNotTouched()
    {
        await using var sp = NewServices();
        var db = sp.GetRequiredService<AppDbContext>();
        db.Users.Add(new AppUser { Id = Guid.NewGuid(), Username = "old.admin", PasswordHash = "x", Role = UserRole.ADMIN, IsActive = false });
        await db.SaveChangesAsync();

        await RunAsync(sp, Options("new.admin")); // nobody active, so the bootstrap creates one

        Assert.Equal(new[] { "new.admin", "old.admin" }, db.Users.Select(u => u.Username).AsEnumerable().Order());
        Assert.False((await db.Users.SingleAsync(u => u.Username == "old.admin")).IsActive);
    }

    [Theory]
    [InlineData(UserRole.VETTING, true)]
    [InlineData(UserRole.ADMIN, false)]
    public async Task ATakenUsername_StopsTheStartup_AndTheExistingAccountIsLeftExactlyAsItWas(UserRole role, bool active)
    {
        await using var sp = NewServices();
        var db = sp.GetRequiredService<AppDbContext>();
        db.Users.Add(new AppUser { Id = Guid.NewGuid(), Username = "first.admin", PasswordHash = "untouched", Role = role, IsActive = active });
        await db.SaveChangesAsync();

        var error = await Assert.ThrowsAsync<InvalidOperationException>(() => RunAsync(sp, Options("first.admin")));

        Assert.Contains("first.admin", error.Message);
        Assert.DoesNotContain(Password, error.Message);
        var user = await db.Users.SingleAsync();
        Assert.Equal((role, active, "untouched"), (user.Role, user.IsActive, user.PasswordHash)); // never promoted, never reset
    }

    [Theory]
    [InlineData("first.admin", null)]
    [InlineData("first.admin", "")]
    [InlineData(null, Password)]
    [InlineData("  ", Password)]
    public async Task AHalfSetConfiguration_StopsTheStartup(string? username, string? password)
    {
        await using var sp = NewServices();

        var error = await Assert.ThrowsAsync<InvalidOperationException>(() => RunAsync(sp, Options(username, password)));

        Assert.Contains("half configured", error.Message);
        Assert.Empty(sp.GetRequiredService<AppDbContext>().Users);
    }

    [Theory]
    [InlineData("first.admin", "short")]
    [InlineData("first.admin", "aaaaaaaaaaaaaaaa")]
    [InlineData("first.admin", "my-first.admin-password")] // contains the username
    [InlineData("no", Password)]
    public async Task AnUnacceptableConfiguration_StopsTheStartup_WithoutPrintingThePassword(string username, string password)
    {
        await using var sp = NewServices();

        var error = await Assert.ThrowsAsync<InvalidOperationException>(() => RunAsync(sp, Options(username, password)));

        Assert.DoesNotContain(password, error.Message);
        Assert.Empty(sp.GetRequiredService<AppDbContext>().Users);
    }

    [Fact]
    public void TheOptions_NeverPrintThePassword()
    {
        var text = Options().ToString();

        Assert.DoesNotContain(Password, text);
        Assert.Contains("first.admin", text);
    }

    // ── through the real start-up ──────────────────────────────────────────────────

    private sealed class BootstrapFactory : ApiFactory
    {
        protected override bool SeedUsers => false; // start from nothing, like a fresh deployment

        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            base.ConfigureWebHost(builder);
            builder.UseSetting("Bootstrap:AdminUsername", "first.admin");
            // Qualified: a bare "Password" here is ApiFactory.Password (the seeded users'), which is a different value.
            builder.UseSetting("Bootstrap:AdminPassword", AdminBootstrapperTests.Password);
        }
    }

    [Fact]
    public async Task OnAFreshDeployment_TheFirstAdminCanSignIn_AndThenCreateEveryoneElse()
    {
        await using var factory = new BootstrapFactory();
        var client = factory.CreateClient();

        var login = await client.PostAsJsonAsync("/api/auth/login", new { username = "first.admin", password = AdminBootstrapperTests.Password });
        Assert.Equal(HttpStatusCode.OK, login.StatusCode);
        var body = await login.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("ADMIN", body.GetProperty("role").GetString());

        client.DefaultRequestHeaders.Authorization = new("Bearer", body.GetProperty("token").GetString());
        var create = await client.PostAsJsonAsync("/api/admin/users",
            new { username = "kgosi.collector", role = "CBO_COLLECTION", cboId = "cbo-12", password = "kgosis-own-passphrase-9" });
        Assert.Equal(HttpStatusCode.Created, create.StatusCode);
        Assert.Equal(HttpStatusCode.OK,
            (await factory.CreateClient().PostAsJsonAsync("/api/auth/login", new { username = "kgosi.collector", password = "kgosis-own-passphrase-9" })).StatusCode);
    }
}
