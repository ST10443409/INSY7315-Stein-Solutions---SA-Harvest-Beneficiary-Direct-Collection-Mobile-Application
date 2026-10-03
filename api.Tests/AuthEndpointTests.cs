using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Claims;
using System.Text;
using System.Text.Json;
using api.Data;
using api.Models;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;

namespace api.Tests;

public class AuthEndpointTests : IClassFixture<ApiFactory>
{
    private readonly ApiFactory _factory;

    public AuthEndpointTests(ApiFactory factory) => _factory = factory;

    // ── helpers ────────────────────────────────────────────────────────────────────

    private record LoginBody(string Token, string Role, DateTimeOffset ExpiresAt, string? CboId = null);

    private Task<HttpResponseMessage> PostLogin(string username, string password) =>
        _factory.CreateClient().PostAsJsonAsync("/api/auth/login", new { username, password });

    private async Task<string> LoginToken(string username)
    {
        var response = await PostLogin(username, ApiFactory.Password);
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<LoginBody>(new JsonSerializerOptions(JsonSerializerDefaults.Web)))!.Token;
    }

    private async Task<HttpStatusCode> Get(string path, string? token)
    {
        var client = _factory.CreateClient();
        if (token != null) client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return (await client.GetAsync(path)).StatusCode;
    }

    private static string ForgeToken(
        string key = ApiFactory.SigningKey, string issuer = ApiFactory.Issuer, string audience = ApiFactory.Audience,
        DateTime? expires = null, string role = "ADMIN") =>
        new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
        {
            Issuer = issuer,
            Audience = audience,
            Expires = expires ?? DateTime.UtcNow.AddMinutes(10),
            NotBefore = (expires ?? DateTime.UtcNow.AddMinutes(10)).AddMinutes(-20),
            Subject = new ClaimsIdentity(new[] { new Claim("sub", "1"), new Claim("name", "forged"), new Claim("role", role) }),
            SigningCredentials = new SigningCredentials(new SymmetricSecurityKey(Encoding.UTF8.GetBytes(key)), SecurityAlgorithms.HmacSha256),
        });

    // ── login ──────────────────────────────────────────────────────────────────────

    [Theory]
    [InlineData("cbo_test_user", "CBO_COLLECTION")]
    [InlineData("vetting_test_user", "VETTING")]
    [InlineData("admin_test_user", "ADMIN")]
    public async Task Login_ReturnsAJwt_WithTheRoleClaimExactlyAsTheAndroidEnum(string username, string expectedRole)
    {
        var response = await PostLogin(username, ApiFactory.Password);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = (await response.Content.ReadFromJsonAsync<LoginBody>(new JsonSerializerOptions(JsonSerializerDefaults.Web)))!;
        Assert.Equal(expectedRole, body.Role);

        var jwt = new JsonWebTokenHandler().ReadJsonWebToken(body.Token);
        Assert.Equal(expectedRole, jwt.GetClaim("role").Value);
        Assert.Equal(ApiFactory.Issuer, jwt.Issuer);
        Assert.Contains(ApiFactory.Audience, jwt.Audiences);
        Assert.Equal("HS256", jwt.Alg);
        Assert.Equal(username, jwt.GetClaim("name").Value);
    }

    [Fact]
    public async Task Login_OfACollector_ReturnsTheirCbo_AndPutsItInTheToken()
    {
        var response = await PostLogin("cbo_test_user", ApiFactory.Password);

        var body = (await response.Content.ReadFromJsonAsync<LoginBody>(new JsonSerializerOptions(JsonSerializerDefaults.Web)))!;
        Assert.Equal(ApiFactory.CboId, body.CboId);
        Assert.Equal(ApiFactory.CboId, new JsonWebTokenHandler().ReadJsonWebToken(body.Token).GetClaim("cbo_id").Value);
    }

    [Theory]
    [InlineData("vetting_test_user")]
    [InlineData("admin_test_user")]
    public async Task Login_OfUsersWithoutACbo_ReturnsNone_AndNoCboClaim(string username)
    {
        var response = await PostLogin(username, ApiFactory.Password);

        var body = (await response.Content.ReadFromJsonAsync<LoginBody>(new JsonSerializerOptions(JsonSerializerDefaults.Web)))!;
        Assert.Null(body.CboId);
        Assert.DoesNotContain(new JsonWebTokenHandler().ReadJsonWebToken(body.Token).Claims, c => c.Type == "cbo_id");
    }

    [Fact]
    public async Task Login_TokenLifetime_IsShort()
    {
        var body = (await (await PostLogin("admin_test_user", ApiFactory.Password)).Content
            .ReadFromJsonAsync<LoginBody>(new JsonSerializerOptions(JsonSerializerDefaults.Web)))!;

        var lifetime = new JsonWebTokenHandler().ReadJsonWebToken(body.Token).ValidTo - DateTime.UtcNow;
        Assert.InRange(lifetime, TimeSpan.FromMinutes(55), TimeSpan.FromMinutes(61));
    }

    [Fact]
    public async Task Login_IsCaseInsensitiveOnUsername_AndIgnoresSurroundingSpaces()
    {
        Assert.Equal(HttpStatusCode.OK, (await PostLogin("  CBO_Test_User ", ApiFactory.Password)).StatusCode);
    }

    [Fact]
    public async Task Login_WrongPassword_And_UnknownUser_And_DisabledUser_AreIndistinguishable401s()
    {
        var wrongPassword = await PostLogin("cbo_test_user", "wrong");
        var unknownUser = await PostLogin("nobody_at_all", "wrong");
        var disabled = await PostLogin("disabled_user", ApiFactory.Password);

        foreach (var response in new[] { wrongPassword, unknownUser, disabled })
            Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);

        var bodies = new[]
        {
            await wrongPassword.Content.ReadAsStringAsync(),
            await unknownUser.Content.ReadAsStringAsync(),
            await disabled.Content.ReadAsStringAsync(),
        };
        Assert.Single(bodies.Distinct()); // identical: nothing reveals whether the username exists
        Assert.DoesNotContain("exist", bodies[0], StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("password is", bodies[0], StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("token", bodies[0], StringComparison.OrdinalIgnoreCase);
    }

    [Theory]
    [InlineData("", "x")]
    [InlineData("cbo_test_user", "")]
    public async Task Login_MissingFields_Is400(string username, string password)
    {
        Assert.Equal(HttpStatusCode.BadRequest, (await PostLogin(username, password)).StatusCode);
    }

    [Fact]
    public async Task Passwords_AreStoredHashed_NeverInPlaintext()
    {
        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        var hasher = scope.ServiceProvider.GetRequiredService<IPasswordHasher<AppUser>>();

        foreach (var user in db.Users.ToList())
        {
            Assert.NotEqual(ApiFactory.Password, user.PasswordHash);
            Assert.DoesNotContain(ApiFactory.Password, user.PasswordHash);
            Assert.NotEqual(PasswordVerificationResult.Failed, hasher.VerifyHashedPassword(user, user.PasswordHash, ApiFactory.Password));
        }
        // Per-user salt: the same password produces different hashes.
        var hashes = db.Users.Select(u => u.PasswordHash).ToList();
        Assert.Equal(hashes.Count, hashes.Distinct().Count());
    }

    // ── authentication ─────────────────────────────────────────────────────────────

    [Fact]
    public async Task ProtectedEndpoint_WithoutToken_Is401()
    {
        Assert.Equal(HttpStatusCode.Unauthorized, await Get("/api/access-demo/any", null));
        Assert.Equal(HttpStatusCode.Unauthorized, await Get("/api/auth/me", null));
    }

    [Fact]
    public async Task Me_ReturnsTheCallersIdentityAndRole()
    {
        var client = _factory.CreateClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", await LoginToken("vetting_test_user"));

        var me = await client.GetFromJsonAsync<JsonElement>("/api/auth/me");

        Assert.Equal("vetting_test_user", me.GetProperty("username").GetString());
        Assert.Equal("VETTING", me.GetProperty("role").GetString());
    }

    [Fact]
    public async Task TheRetiredGenericSyncEndpoint_IsGone()
    {
        // POST /api/sync queued an arbitrary payload in memory and posted it to the simulator; production would have accepted
        // data and dropped it. The real endpoints are /api/cbo-collection/sync and /api/vetting/sync.
        var client = _factory.CreateClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", await LoginToken("admin_test_user"));

        var response = await client.PostAsJsonAsync("/api/sync", new { id = "1", data = "d" });

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task ExpiredToken_Is401()
    {
        var expired = ForgeToken(expires: DateTime.UtcNow.AddMinutes(-5));

        Assert.Equal(HttpStatusCode.Unauthorized, await Get("/api/access-demo/any", expired));
    }

    [Fact]
    public async Task TokenSignedWithTheWrongKey_Is401()
    {
        var forged = ForgeToken(key: "some-other-attackers-key-0123456789abcdef");

        Assert.Equal(HttpStatusCode.Unauthorized, await Get("/api/access-demo/admin", forged));
    }

    [Theory]
    [InlineData("wrong-issuer", ApiFactory.Audience)]
    [InlineData(ApiFactory.Issuer, "wrong-audience")]
    public async Task TokenWithTheWrongIssuerOrAudience_Is401(string issuer, string audience)
    {
        var token = ForgeToken(issuer: issuer, audience: audience);

        Assert.Equal(HttpStatusCode.Unauthorized, await Get("/api/access-demo/any", token));
    }

    [Fact]
    public async Task TamperedPayload_IsRejected_SoARoleCannotBeUpgraded()
    {
        var parts = (await LoginToken("cbo_test_user")).Split('.');
        var payload = Encoding.UTF8.GetString(Base64UrlEncoder.DecodeBytes(parts[1])).Replace("CBO_COLLECTION", "ADMIN");
        var tampered = $"{parts[0]}.{Base64UrlEncoder.Encode(payload)}.{parts[2]}";

        Assert.Equal(HttpStatusCode.Unauthorized, await Get("/api/access-demo/admin", tampered));
    }

    // ── authorization (roles) ──────────────────────────────────────────────────────

    [Theory]
    // user,               path,          expected
    [InlineData("cbo_test_user", "any", HttpStatusCode.OK)]
    [InlineData("cbo_test_user", "cbo", HttpStatusCode.OK)]
    [InlineData("cbo_test_user", "vetting", HttpStatusCode.Forbidden)]
    [InlineData("cbo_test_user", "admin", HttpStatusCode.Forbidden)]
    [InlineData("cbo_test_user", "vetting-or-admin", HttpStatusCode.Forbidden)]
    [InlineData("vetting_test_user", "any", HttpStatusCode.OK)]
    [InlineData("vetting_test_user", "vetting", HttpStatusCode.OK)]
    [InlineData("vetting_test_user", "vetting-or-admin", HttpStatusCode.OK)]
    [InlineData("vetting_test_user", "cbo", HttpStatusCode.Forbidden)]
    [InlineData("vetting_test_user", "admin", HttpStatusCode.Forbidden)]
    [InlineData("admin_test_user", "any", HttpStatusCode.OK)]
    [InlineData("admin_test_user", "admin", HttpStatusCode.OK)]
    [InlineData("admin_test_user", "vetting-or-admin", HttpStatusCode.OK)]
    [InlineData("admin_test_user", "cbo", HttpStatusCode.Forbidden)]
    [InlineData("admin_test_user", "vetting", HttpStatusCode.Forbidden)]
    public async Task RoleScopedEndpoints_AllowOnlyTheirRoles(string username, string path, HttpStatusCode expected)
    {
        Assert.Equal(expected, await Get($"/api/access-demo/{path}", await LoginToken(username)));
    }

    // ── configuration ──────────────────────────────────────────────────────────────

    [Fact]
    public void App_RefusesToStart_WithAWeakSigningKey()
    {
        using var weak = new ApiFactory { SigningKeyOverride = "too-short" };

        Assert.ThrowsAny<Exception>(() => weak.CreateClient());
    }
}
