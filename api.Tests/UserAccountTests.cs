using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Claims;
using System.Text;
using System.Text.Json;
using api.Data;
using api.Models;
using api.Services;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;

namespace api.Tests;

/// <summary>
/// A token is only as good as the account behind it: the signature and lifetime are checked by the framework, and
/// <c>TokenAccountCheck</c> then ties the token to a live account with a current stamp. These tests forge tokens that pass the
/// first and fail the second, which is exactly what a deactivated user's still-unexpired token is.
/// </summary>
public class UserAccountTests : IClassFixture<ApiFactory>
{
    private readonly ApiFactory _factory;

    public UserAccountTests(ApiFactory factory) => _factory = factory;

    // ── tokens that are signed and unexpired, but name something that is not (or no longer) a live account ───────────

    private static string Token(Guid? userId, string? stamp, string role = "ADMIN")
    {
        var claims = new List<Claim> { new("name", "someone"), new("role", role) };
        if (userId is not null) claims.Add(new Claim("sub", userId.Value.ToString()));
        if (stamp is not null) claims.Add(new Claim("stamp", stamp));
        return new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
        {
            Issuer = ApiFactory.Issuer,
            Audience = ApiFactory.Audience,
            Expires = DateTime.UtcNow.AddMinutes(30),
            Subject = new ClaimsIdentity(claims),
            SigningCredentials = new SigningCredentials(
                new SymmetricSecurityKey(Encoding.UTF8.GetBytes(ApiFactory.SigningKey)), SecurityAlgorithms.HmacSha256),
        });
    }

    private async Task<HttpStatusCode> MeWithAsync(string token)
    {
        var client = _factory.CreateClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return (await client.GetAsync("/api/auth/me")).StatusCode;
    }

    private (Guid Id, Guid Stamp) Lookup(string username)
    {
        using var scope = _factory.Services.CreateScope();
        var user = scope.ServiceProvider.GetRequiredService<AppDbContext>().Users.Single(u => u.Username == username);
        return (user.Id, user.SecurityStamp);
    }

    [Fact]
    public async Task ATokenForARealActiveAccountWithItsCurrentStamp_IsAccepted()
    {
        var (id, stamp) = Lookup("vetting_test_user");

        Assert.Equal(HttpStatusCode.OK, await MeWithAsync(Token(id, stamp.ToString(), "VETTING")));
    }

    [Fact]
    public async Task ATokenForAnAccountThatDoesNotExist_Is401()
    {
        Assert.Equal(HttpStatusCode.Unauthorized, await MeWithAsync(Token(Guid.NewGuid(), Guid.NewGuid().ToString())));
    }

    [Fact]
    public async Task ATokenWithTheWrongStamp_Is401()
    {
        var (id, _) = Lookup("vetting_test_user");

        Assert.Equal(HttpStatusCode.Unauthorized, await MeWithAsync(Token(id, Guid.NewGuid().ToString(), "VETTING")));
    }

    [Theory]
    [InlineData(false, true)]   // no user id
    [InlineData(true, false)]   // no stamp: also what a token issued before stamps existed looks like
    [InlineData(false, false)]
    public async Task ATokenThatDoesNotSayWhoItIs_Is401(bool hasUserId, bool hasStamp)
    {
        var (id, stamp) = Lookup("vetting_test_user");

        var token = Token(hasUserId ? id : null, hasStamp ? stamp.ToString() : null, "VETTING");

        Assert.Equal(HttpStatusCode.Unauthorized, await MeWithAsync(token));
    }

    [Theory]
    [InlineData("not-a-guid")]
    [InlineData("")]
    public async Task AMalformedStamp_Is401(string stamp)
    {
        var (id, _) = Lookup("vetting_test_user");

        Assert.Equal(HttpStatusCode.Unauthorized, await MeWithAsync(Token(id, stamp, "VETTING")));
    }

    [Fact]
    public async Task ATokenForAnAccountDeactivatedBehindTheApisBack_Is401()
    {
        // Straight in the database, so nothing but the per-request account check can notice.
        var client = _factory.CreateClient();
        var login = await client.PostAsJsonAsync("/api/auth/login", new { username = "cbo_other_user", password = ApiFactory.Password });
        var token = (await login.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("token").GetString()!;
        Assert.Equal(HttpStatusCode.OK, await MeWithAsync(token));

        using (var scope = _factory.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            db.Users.Single(u => u.Username == "cbo_other_user").IsActive = false;
            await db.SaveChangesAsync();
        }

        Assert.Equal(HttpStatusCode.Unauthorized, await MeWithAsync(token));
    }

    [Fact]
    public async Task EveryTokenTheApiIssues_CarriesTheStampOfItsAccount()
    {
        var login = await _factory.CreateClient().PostAsJsonAsync("/api/auth/login", new { username = "admin_test_user", password = ApiFactory.Password });
        var token = (await login.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("token").GetString()!;

        var payload = JsonDocument.Parse(Base64UrlEncoder.Decode(token.Split('.')[1])).RootElement;

        Assert.Equal(Lookup("admin_test_user").Stamp.ToString(), payload.GetProperty("stamp").GetString());
    }

    // ── the guard the HTTP API cannot reach on its own ─────────────────────────────

    [Fact]
    public async Task TheLastActiveAdmin_CanNeverBeDeactivatedOrDemoted_EvenByAnActorWhoIsNotThemselves()
    {
        // Through the API an Admin cannot touch themselves, so there is always another admin (the caller) and this guard cannot
        // fire; it exists for a race between two Admins, and for any caller that is not an active Admin. Tested on the service.
        var db = new AppDbContext(new DbContextOptionsBuilder<AppDbContext>().UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        var solo = new AppUser { Id = Guid.NewGuid(), Username = "solo.admin", PasswordHash = "x", Role = UserRole.ADMIN };
        db.Users.Add(solo);
        await db.SaveChangesAsync();
        var service = new UserManagementService(db, new PasswordHasher<AppUser>(), TimeProvider.System, NullLogger<UserManagementService>.Instance);
        var ghost = new AdminActor(Guid.NewGuid(), "ghost");

        var deactivate = await service.UpdateAsync(ghost, solo.Id, new api.DTOs.UpdateUserRequest { IsActive = false });
        var demote = await service.UpdateAsync(ghost, solo.Id, new api.DTOs.UpdateUserRequest { Role = "VETTING" });

        Assert.Equal(UserResultStatus.Conflict, deactivate.Status);
        Assert.Equal(UserResultStatus.Conflict, demote.Status);
        Assert.Contains("no active administrator", deactivate.Message);
        var stored = await db.Users.SingleAsync();
        Assert.True(stored.IsActive);
        Assert.Equal(UserRole.ADMIN, stored.Role);
    }

    [Fact]
    public async Task AnotherAdminExisting_LetsAnAdminBeDeactivated()
    {
        var db = new AppDbContext(new DbContextOptionsBuilder<AppDbContext>().UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        var first = new AppUser { Id = Guid.NewGuid(), Username = "first.admin", PasswordHash = "x", Role = UserRole.ADMIN };
        var second = new AppUser { Id = Guid.NewGuid(), Username = "second.admin", PasswordHash = "x", Role = UserRole.ADMIN };
        db.Users.AddRange(first, second);
        await db.SaveChangesAsync();
        var service = new UserManagementService(db, new PasswordHasher<AppUser>(), TimeProvider.System, NullLogger<UserManagementService>.Instance);

        var result = await service.UpdateAsync(new AdminActor(second.Id, "second.admin"), first.Id, new api.DTOs.UpdateUserRequest { IsActive = false });

        Assert.Equal(UserResultStatus.Ok, result.Status);
        Assert.False((await db.Users.SingleAsync(u => u.Id == first.Id)).IsActive);
    }
}
