using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using api.Data;
using api.Models;
using Microsoft.Extensions.DependencyInjection;

namespace api.Tests;

/// <summary>
/// Account management (<c>/api/admin/users</c>): creating, validating, listing, changing, deactivating and resetting accounts,
/// and, above all, that doing so really ends the person's sessions. Who may call these endpoints at all is proved for every
/// role by <see cref="RoleAuthorizationMatrixTests"/>.
/// </summary>
public class UserManagementEndpointTests : IClassFixture<ApiFactory>
{
    private const string StrongPassword = "a-long-enough-passphrase-1";
    private const string OtherPassword = "another-long-passphrase-2";
    private static readonly JsonSerializerOptions Web = new(JsonSerializerDefaults.Web);

    private readonly ApiFactory _factory;

    public UserManagementEndpointTests(ApiFactory factory) => _factory = factory;

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static string UniqueName(string prefix = "u") => $"{prefix}{Guid.NewGuid():N}"[..20];

    private static async Task<string> LoginAsync(HttpClient client, string username, string password)
    {
        var response = await client.PostAsJsonAsync("/api/auth/login", new { username, password });
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("token").GetString()!;
    }

    private async Task<HttpClient> SignedInAsync(string username, string password = ApiFactory.Password)
    {
        var client = _factory.CreateClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", await LoginAsync(client, username, password));
        return client;
    }

    private Task<HttpClient> AdminAsync() => SignedInAsync("admin_test_user");

    private static async Task<JsonElement> BodyAsync(HttpResponseMessage response) => await response.Content.ReadFromJsonAsync<JsonElement>();

    private static Task<HttpResponseMessage> CreateAsync(
        HttpClient admin, string username, string role, string? cboId = null, string password = StrongPassword) =>
        admin.PostAsJsonAsync("/api/admin/users", new { username, role, cboId, password });

    /// <summary>Creates an account and returns its id.</summary>
    private static async Task<string> CreatedAsync(HttpClient admin, string username, string role = "VETTING", string? cboId = null, string password = StrongPassword)
    {
        var response = await CreateAsync(admin, username, role, cboId, password);
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        return (await BodyAsync(response)).GetProperty("data").GetProperty("id").GetString()!;
    }

    private static Task<HttpResponseMessage> PatchAsync(HttpClient admin, string id, object body) =>
        admin.PatchAsJsonAsync($"/api/admin/users/{id}", body);

    private static async Task<HttpStatusCode> MeAsync(HttpClient client) => (await client.GetAsync("/api/auth/me")).StatusCode;

    /// <summary>Whether a validation error names the field. Case-insensitive: the framework's own checks (a missing value) say
    /// "Password", the service's rules say "password".</summary>
    private static bool Names(JsonElement error, string field) =>
        error.GetProperty("details").EnumerateObject().Any(p => string.Equals(p.Name, field, StringComparison.OrdinalIgnoreCase));

    private HttpClient TokenClient(string token)
    {
        var client = _factory.CreateClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return client;
    }

    private AppUser StoredUser(string username)
    {
        using var scope = _factory.Services.CreateScope();
        return scope.ServiceProvider.GetRequiredService<AppDbContext>().Users.Single(u => u.Username == username);
    }

    // ── creating ───────────────────────────────────────────────────────────────────

    [Fact]
    public async Task ACollector_CanBeCreated_AndSignsInWithTheirRoleAndCbo()
    {
        var admin = await AdminAsync();
        var username = UniqueName("coll");

        var response = await CreateAsync(admin, username, "CBO_COLLECTION", "cbo-777");

        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        var data = (await BodyAsync(response)).GetProperty("data");
        Assert.Equal(username, data.GetProperty("username").GetString());
        Assert.Equal("CBO_COLLECTION", data.GetProperty("role").GetString());
        Assert.Equal("cbo-777", data.GetProperty("cboId").GetString());
        Assert.True(data.GetProperty("isActive").GetBoolean());

        var login = await _factory.CreateClient().PostAsJsonAsync("/api/auth/login", new { username, password = StrongPassword });
        Assert.Equal(HttpStatusCode.OK, login.StatusCode);
        var signedIn = await BodyAsync(login);
        Assert.Equal("CBO_COLLECTION", signedIn.GetProperty("role").GetString());
        Assert.Equal("cbo-777", signedIn.GetProperty("cboId").GetString());
    }

    [Theory]
    [InlineData("VETTING")]
    [InlineData("ADMIN")]
    [InlineData("vetting")] // the role is accepted in any case, like the other endpoints
    public async Task OtherRoles_CanBeCreated_WithoutACbo(string role)
    {
        var admin = await AdminAsync();

        var response = await CreateAsync(admin, UniqueName(), role);

        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        Assert.Equal(JsonValueKind.Null, (await BodyAsync(response)).GetProperty("data").GetProperty("cboId").ValueKind);
    }

    [Fact]
    public async Task TheUsername_IsTrimmedAndLowerCased_AndCannotBeTakenTwice()
    {
        var admin = await AdminAsync();
        var name = UniqueName("case");

        var first = await CreateAsync(admin, $"  {name.ToUpperInvariant()}  ", "VETTING");
        Assert.Equal(HttpStatusCode.Created, first.StatusCode);
        Assert.Equal(name, (await BodyAsync(first)).GetProperty("data").GetProperty("username").GetString());

        var second = await CreateAsync(admin, name, "VETTING");
        Assert.Equal(HttpStatusCode.Conflict, second.StatusCode);
        Assert.Equal("CONFLICT", (await BodyAsync(second)).GetProperty("error").GetProperty("code").GetString());
    }

    [Theory]
    // username,            role,             cboId,    password,                          the field that must be named
    [InlineData("ab",       "VETTING",        null,     StrongPassword,                    "username")] // too short
    [InlineData("has space","VETTING",        null,     StrongPassword,                    "username")]
    [InlineData("OK",       "ROOT",           null,     StrongPassword,                    "role")]
    [InlineData("OK",       "",               null,     StrongPassword,                    "role")]
    [InlineData("OK",       "CBO_COLLECTION", null,     StrongPassword,                    "cboId")]    // a collector needs a CBO
    [InlineData("OK",       "CBO_COLLECTION", "  ",     StrongPassword,                    "cboId")]
    [InlineData("OK",       "VETTING",        "cbo-1",  StrongPassword,                    "cboId")]    // the others must not have one
    [InlineData("OK",       "VETTING",        null,     "short",                           "password")]
    [InlineData("OK",       "VETTING",        null,     "aaaaaaaaaaaaaaaaaaaa",            "password")] // repetitive
    [InlineData("OK",       "VETTING",        null,     "            ",                    "password")] // only spaces
    public async Task AnInvalidRequest_IsRefused_NamingTheField_AndCreatesNothing(string username, string role, string? cboId, string password, string field)
    {
        var admin = await AdminAsync();
        var name = username == "OK" ? UniqueName("inv") : username;

        var response = await CreateAsync(admin, name, role, cboId, password);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var error = (await BodyAsync(response)).GetProperty("error");
        Assert.Equal("VALIDATION_FAILED", error.GetProperty("code").GetString());
        Assert.True(Names(error, field), $"expected a problem named '{field}'");
        using var scope = _factory.Services.CreateScope();
        Assert.DoesNotContain(scope.ServiceProvider.GetRequiredService<AppDbContext>().Users, u => u.Username == name.Trim().ToLowerInvariant());
    }

    [Fact]
    public async Task APasswordContainingTheUsername_IsRefused()
    {
        var admin = await AdminAsync();
        var name = UniqueName("who");

        var response = await CreateAsync(admin, name, "VETTING", password: $"my-{name}-secret");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.True((await BodyAsync(response)).GetProperty("error").GetProperty("details").TryGetProperty("password", out _));
    }

    [Fact]
    public async Task ThePassword_IsNeverReturned_AndOnlyItsHashIsStored()
    {
        var admin = await AdminAsync();
        var name = UniqueName("hash");

        var response = await CreateAsync(admin, name, "VETTING");
        var text = await response.Content.ReadAsStringAsync();

        Assert.DoesNotContain(StrongPassword, text);
        Assert.DoesNotContain("passwordHash", text, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("securityStamp", text, StringComparison.OrdinalIgnoreCase);
        var stored = StoredUser(name);
        Assert.NotEqual(StrongPassword, stored.PasswordHash);
        Assert.DoesNotContain(StrongPassword, stored.PasswordHash);
    }

    [Fact]
    public async Task OnlyAnAdmin_CanCreateAccounts()
    {
        var vetting = await SignedInAsync("vetting_test_user");
        var name = UniqueName("sneaky");

        var response = await CreateAsync(vetting, name, "ADMIN");

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        using var scope = _factory.Services.CreateScope();
        Assert.DoesNotContain(scope.ServiceProvider.GetRequiredService<AppDbContext>().Users, u => u.Username == name);
    }

    // ── listing and reading ────────────────────────────────────────────────────────

    [Fact]
    public async Task TheList_CanBeFilteredByRoleActiveAndSearch_AndIsPaged()
    {
        var admin = await AdminAsync();
        var tag = $"lst{Guid.NewGuid():N}"[..10];
        await CreatedAsync(admin, $"{tag}-a", "VETTING");
        await CreatedAsync(admin, $"{tag}-b", "VETTING");
        var inactiveId = await CreatedAsync(admin, $"{tag}-c", "ADMIN");
        Assert.Equal(HttpStatusCode.OK, (await PatchAsync(admin, inactiveId, new { isActive = false })).StatusCode);

        var all = (await BodyAsync(await admin.GetAsync($"/api/admin/users?search={tag}"))).GetProperty("data");
        Assert.Equal(3, all.GetProperty("totalCount").GetInt32());
        Assert.Equal(new[] { $"{tag}-a", $"{tag}-b", $"{tag}-c" }, all.GetProperty("items").EnumerateArray().Select(i => i.GetProperty("username").GetString()));

        var vetting = (await BodyAsync(await admin.GetAsync($"/api/admin/users?search={tag}&role=vetting"))).GetProperty("data");
        Assert.Equal(2, vetting.GetProperty("totalCount").GetInt32());

        var inactive = (await BodyAsync(await admin.GetAsync($"/api/admin/users?search={tag}&active=false"))).GetProperty("data");
        Assert.Equal($"{tag}-c", Assert.Single(inactive.GetProperty("items").EnumerateArray()).GetProperty("username").GetString());

        var page1 = (await BodyAsync(await admin.GetAsync($"/api/admin/users?search={tag}&page=1&pageSize=2"))).GetProperty("data");
        Assert.Equal(2, page1.GetProperty("items").GetArrayLength());
        Assert.True(page1.GetProperty("hasMore").GetBoolean());
        var page2 = (await BodyAsync(await admin.GetAsync($"/api/admin/users?search={tag}&page=2&pageSize=2"))).GetProperty("data");
        Assert.Equal(1, page2.GetProperty("items").GetArrayLength());
        Assert.False(page2.GetProperty("hasMore").GetBoolean());
    }

    [Theory]
    [InlineData("role=ROOT")]
    [InlineData("page=0")]
    [InlineData("pageSize=0")]
    [InlineData("pageSize=101")]
    public async Task ABadListQuery_Is400(string query)
    {
        var admin = await AdminAsync();

        Assert.Equal(HttpStatusCode.BadRequest, (await admin.GetAsync($"/api/admin/users?{query}")).StatusCode);
    }

    [Fact]
    public async Task AnAccount_ShowsItsHistory_NewestFirst_WithoutAnySecret()
    {
        var admin = await AdminAsync();
        var id = await CreatedAsync(admin, UniqueName("hist"), "VETTING");
        await PatchAsync(admin, id, new { role = "ADMIN" });
        await admin.PostAsJsonAsync($"/api/admin/users/{id}/reset-password", new { newPassword = OtherPassword });

        var response = await admin.GetAsync($"/api/admin/users/{id}");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var text = await response.Content.ReadAsStringAsync();
        Assert.DoesNotContain(StrongPassword, text);
        Assert.DoesNotContain(OtherPassword, text);
        var history = JsonDocument.Parse(text).RootElement.GetProperty("data").GetProperty("history").EnumerateArray().ToList();
        Assert.Equal(new[] { "PASSWORD_RESET", "ROLE_CHANGED", "CREATED" }, history.Select(h => h.GetProperty("action").GetString()));
        Assert.All(history, h => Assert.Equal("admin_test_user", h.GetProperty("actor").GetString()));
        Assert.Equal("VETTING to ADMIN", history[1].GetProperty("detail").GetString());
    }

    [Theory]
    [InlineData("not-a-guid")]
    [InlineData("00000000-0000-0000-0000-000000000001")]
    public async Task AnUnknownAccount_Is404_ForEveryAction(string id)
    {
        var admin = await AdminAsync();

        Assert.Equal(HttpStatusCode.NotFound, (await admin.GetAsync($"/api/admin/users/{id}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await PatchAsync(admin, id, new { isActive = false })).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound,
            (await admin.PostAsJsonAsync($"/api/admin/users/{id}/reset-password", new { newPassword = StrongPassword })).StatusCode);
    }

    // ── changing ───────────────────────────────────────────────────────────────────

    [Fact]
    public async Task ChangingARole_EndsTheOldSession_AndTheNextSignInHasTheNewRole()
    {
        var admin = await AdminAsync();
        var name = UniqueName("role");
        var id = await CreatedAsync(admin, name, "CBO_COLLECTION", "cbo-1");
        var userClient = TokenClient(await LoginAsync(_factory.CreateClient(), name, StrongPassword));
        Assert.Equal(HttpStatusCode.OK, await MeAsync(userClient));

        var change = await PatchAsync(admin, id, new { role = "VETTING" });

        Assert.Equal(HttpStatusCode.OK, change.StatusCode);
        var data = (await BodyAsync(change)).GetProperty("data");
        Assert.Equal("VETTING", data.GetProperty("role").GetString());
        Assert.Equal(JsonValueKind.Null, data.GetProperty("cboId").ValueKind); // moving off the collector role clears the CBO
        Assert.Equal(HttpStatusCode.Unauthorized, await MeAsync(userClient));   // the old token carries the old stamp
        var fresh = await BodyAsync(await _factory.CreateClient().PostAsJsonAsync("/api/auth/login", new { username = name, password = StrongPassword }));
        Assert.Equal("VETTING", fresh.GetProperty("role").GetString());
    }

    [Fact]
    public async Task ACollectorsCbo_CanBeChanged_AndIsRecorded()
    {
        var admin = await AdminAsync();
        var id = await CreatedAsync(admin, UniqueName("cbo"), "CBO_COLLECTION", "cbo-old");

        var change = await PatchAsync(admin, id, new { cboId = "cbo-new" });

        Assert.Equal("cbo-new", (await BodyAsync(change)).GetProperty("data").GetProperty("cboId").GetString());
        var history = (await BodyAsync(await admin.GetAsync($"/api/admin/users/{id}"))).GetProperty("data").GetProperty("history");
        Assert.Contains(history.EnumerateArray(), h => h.GetProperty("action").GetString() == "CBO_CHANGED" && h.GetProperty("detail").GetString() == "cbo-old to cbo-new");
    }

    [Fact]
    public async Task MovingSomeoneToTheCollectorRole_NeedsACbo()
    {
        var admin = await AdminAsync();
        var id = await CreatedAsync(admin, UniqueName("mv"), "VETTING");

        var without = await PatchAsync(admin, id, new { role = "CBO_COLLECTION" });
        Assert.Equal(HttpStatusCode.BadRequest, without.StatusCode);
        Assert.True((await BodyAsync(without)).GetProperty("error").GetProperty("details").TryGetProperty("cboId", out _));

        var with = await PatchAsync(admin, id, new { role = "CBO_COLLECTION", cboId = "cbo-9" });
        Assert.Equal(HttpStatusCode.OK, with.StatusCode);
    }

    [Theory]
    [InlineData("{\"role\":\"ROOT\"}", "role")]
    [InlineData("{\"cboId\":\"cbo-1\"}", "cboId")] // a vetting officer has no CBO
    public async Task AnInvalidChange_IsRefused_AndChangesNothing(string json, string field)
    {
        var admin = await AdminAsync();
        var id = await CreatedAsync(admin, UniqueName("bad"), "VETTING");

        var response = await admin.PatchAsync($"/api/admin/users/{id}", new StringContent(json, System.Text.Encoding.UTF8, "application/json"));

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.True((await BodyAsync(response)).GetProperty("error").GetProperty("details").TryGetProperty(field, out _));
    }

    [Fact]
    public async Task AChangeThatChangesNothing_LeavesTheSessionAndTheHistoryAlone()
    {
        var admin = await AdminAsync();
        var name = UniqueName("noop");
        var id = await CreatedAsync(admin, name, "VETTING");
        var userClient = TokenClient(await LoginAsync(_factory.CreateClient(), name, StrongPassword));

        var response = await PatchAsync(admin, id, new { role = "VETTING", isActive = true });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(HttpStatusCode.OK, await MeAsync(userClient)); // nothing changed, so nobody is signed out
        var history = (await BodyAsync(await admin.GetAsync($"/api/admin/users/{id}"))).GetProperty("data").GetProperty("history");
        Assert.Equal(1, history.GetArrayLength()); // just CREATED
    }

    // ── deactivating ───────────────────────────────────────────────────────────────

    [Fact]
    public async Task ADeactivatedAccount_CannotSignIn_AndItsExistingSessionEndsAtOnce()
    {
        var admin = await AdminAsync();
        var name = UniqueName("gone");
        var id = await CreatedAsync(admin, name, "VETTING");
        var userClient = TokenClient(await LoginAsync(_factory.CreateClient(), name, StrongPassword));
        Assert.Equal(HttpStatusCode.OK, await MeAsync(userClient));

        Assert.Equal(HttpStatusCode.OK, (await PatchAsync(admin, id, new { isActive = false })).StatusCode);

        // The token has an hour left and a valid signature; it is refused anyway, because the account is no longer active.
        Assert.Equal(HttpStatusCode.Unauthorized, await MeAsync(userClient));
        Assert.Equal(HttpStatusCode.Unauthorized,
            (await _factory.CreateClient().PostAsJsonAsync("/api/auth/login", new { username = name, password = StrongPassword })).StatusCode);
    }

    [Fact]
    public async Task AReactivatedAccount_CanSignInAgain_ButTheOldSessionStaysEnded()
    {
        var admin = await AdminAsync();
        var name = UniqueName("back");
        var id = await CreatedAsync(admin, name, "VETTING");
        var oldSession = TokenClient(await LoginAsync(_factory.CreateClient(), name, StrongPassword));
        await PatchAsync(admin, id, new { isActive = false });

        Assert.Equal(HttpStatusCode.OK, (await PatchAsync(admin, id, new { isActive = true })).StatusCode);

        Assert.Equal(HttpStatusCode.Unauthorized, await MeAsync(oldSession));
        Assert.Equal(HttpStatusCode.OK, await MeAsync(TokenClient(await LoginAsync(_factory.CreateClient(), name, StrongPassword))));
    }

    [Fact]
    public async Task AnAdmin_CannotDeactivateOrReRoleThemselves()
    {
        var admin = await AdminAsync();
        var self = (await BodyAsync(await admin.GetAsync("/api/admin/users?search=admin_test_user"))).GetProperty("data")
            .GetProperty("items").EnumerateArray().Single(i => i.GetProperty("username").GetString() == "admin_test_user").GetProperty("id").GetString()!;

        var deactivate = await PatchAsync(admin, self, new { isActive = false });
        var demote = await PatchAsync(admin, self, new { role = "VETTING" });

        Assert.Equal(HttpStatusCode.Conflict, deactivate.StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, demote.StatusCode);
        Assert.Equal(HttpStatusCode.OK, await MeAsync(admin)); // and their own session is untouched
    }

    // ── resetting a password ───────────────────────────────────────────────────────

    [Fact]
    public async Task AResetPassword_ReplacesTheOldOne_AndSignsTheAccountOutEverywhere()
    {
        var admin = await AdminAsync();
        var name = UniqueName("reset");
        var id = await CreatedAsync(admin, name, "VETTING");
        var oldSession = TokenClient(await LoginAsync(_factory.CreateClient(), name, StrongPassword));

        var reset = await admin.PostAsJsonAsync($"/api/admin/users/{id}/reset-password", new { newPassword = OtherPassword });

        Assert.Equal(HttpStatusCode.OK, reset.StatusCode);
        Assert.DoesNotContain(OtherPassword, await reset.Content.ReadAsStringAsync());
        Assert.Equal(HttpStatusCode.Unauthorized, await MeAsync(oldSession)); // a stolen phone's session ends
        Assert.Equal(HttpStatusCode.Unauthorized,
            (await _factory.CreateClient().PostAsJsonAsync("/api/auth/login", new { username = name, password = StrongPassword })).StatusCode);
        Assert.Equal(HttpStatusCode.OK, await MeAsync(TokenClient(await LoginAsync(_factory.CreateClient(), name, OtherPassword))));
    }

    [Theory]
    [InlineData("short")]
    [InlineData("aaaaaaaaaaaaaaaaaaaa")]
    public async Task AWeakNewPassword_IsRefused_AndTheOldOneStillWorks(string newPassword)
    {
        var admin = await AdminAsync();
        var name = UniqueName("weak");
        var id = await CreatedAsync(admin, name, "VETTING");

        var reset = await admin.PostAsJsonAsync($"/api/admin/users/{id}/reset-password", new { newPassword });

        Assert.Equal(HttpStatusCode.BadRequest, reset.StatusCode);
        Assert.True((await BodyAsync(reset)).GetProperty("error").GetProperty("details").TryGetProperty("newPassword", out _));
        Assert.Equal(HttpStatusCode.OK, await MeAsync(TokenClient(await LoginAsync(_factory.CreateClient(), name, StrongPassword))));
    }
}
