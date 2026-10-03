using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Claims;
using System.Text;
using System.Text.Json;
using api.Controllers;
using api.Models;
using api.Services.Foodspace;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Controllers;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;

namespace api.Tests;

/// <summary>
/// The role audit (#52) as a test: every endpoint of the real API, called with a token of every role and with none.
///
/// The table below is the intended access, one row per endpoint, and <c>docs/role-audit-checklist.md</c> mirrors it. Three
/// things keep it honest:
///  - the endpoints are read from the running app, so an endpoint added without a row here fails
///    (<see cref="EveryEndpoint_HasARow_AndEveryRow_IsAnEndpoint"/>), and the roles on each endpoint's attribute must equal the
///    row (<see cref="EveryEndpoint_StatesItsAccess_AndItMatchesTheTable"/>), so the table cannot drift from the code;
///  - each endpoint is then actually called as every role: 401 with no token, 403 for a role not in the row, and neither for a
///    role that is (<see cref="EveryEndpoint_AnswersEachRole_AsTheTableSays"/>);
///  - the checklist document must list every row (<see cref="TheChecklistDocument_ListsEveryEndpoint"/>).
///
/// Admin is deliberate on the two form endpoints: the Admin role gets Form 1 and Form 2 inside the Android app
/// (docs/decisions/0001), so the backend accepts what those screens send. Admin-only endpoints are never open to the others.
/// </summary>
public class RoleAuthorizationMatrixTests : IClassFixture<RoleAuthorizationMatrixTests.MatrixFactory>
{
    private const string Cbo = AppRoles.CboCollection;
    private const string Vetting = AppRoles.Vetting;
    private const string Admin = AppRoles.Admin;

    /// <summary>Who may call an endpoint. <see cref="Anyone"/> is no token at all; <see cref="AnySignedIn"/> is any valid token.</summary>
    private static readonly string[] Anyone = { "ANONYMOUS" };
    private static readonly string[] AnySignedIn = { Cbo, Vetting, Admin };

    /// <summary>Method, route, who may call it. Route parameters are written as in the code.</summary>
    public static readonly IReadOnlyList<(string Method, string Route, string[] Allowed)> Table = new (string, string, string[])[]
    {
        ("POST", "api/cbo-collection/sync", new[] { Cbo, Admin }),
        ("POST", "api/vetting/sync", new[] { Vetting, Admin }),
        ("GET", "api/vetting/records", new[] { Vetting, Admin }),
        ("GET", "api/admin/user-activity", new[] { Admin }),
        ("GET", "api/admin/sync-status", new[] { Admin }),
        ("GET", "api/admin/sync-status/attention", new[] { Admin }),
        ("GET", "api/admin/sync-status/{id}", new[] { Admin }),
        ("POST", "api/admin/sync-status/{id}/retry", new[] { Admin }),
        ("POST", "api/admin/sync-status/{id}/dismiss", new[] { Admin }),
        ("GET", "api/health", Anyone),
        ("POST", "api/auth/login", Anyone),
        ("GET", "api/auth/me", AnySignedIn),
    };

    private static readonly string[] Roles = { Cbo, Vetting, Admin };
    private static readonly Dictionary<string, string> UserFor = new()
    {
        [Cbo] = "cbo_test_user",
        [Vetting] = "vetting_test_user",
        [Admin] = "admin_test_user",
    };

    /// <summary>The real API with Foodspace replaced by a stub, so calling every endpoint never leaves the process.</summary>
    public sealed class MatrixFactory : ApiFactory
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            base.ConfigureWebHost(builder);
            builder.ConfigureServices(services =>
            {
                services.RemoveAll<IFoodspaceApiClient>();
                services.AddSingleton<IFoodspaceApiClient, StubFoodspace>();
            });
        }
    }

    private sealed class StubFoodspace : IFoodspaceApiClient
    {
        public Task<FoodspaceResult> SubmitCboCollectionAsync(CboCollection collection, CancellationToken cancellationToken = default) =>
            Task.FromResult(FoodspaceResult.Ok);

        public Task<FoodspaceResult> SubmitVettingDecisionAsync(VettingDecision decision, CancellationToken cancellationToken = default) =>
            Task.FromResult(FoodspaceResult.Ok);

        public Task<FoodspaceBeneficiariesResult> GetBeneficiariesAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult(new FoodspaceBeneficiariesResult(FoodspaceOutcome.Success, Array.Empty<FoodspaceBeneficiaryRecord>()));
    }

    private readonly MatrixFactory _factory;

    public RoleAuthorizationMatrixTests(MatrixFactory factory) => _factory = factory;

    // ── the endpoints, read from the running app ───────────────────────────────────

    private sealed record Endpoint(string Method, string Route, IReadOnlyList<IAuthorizeData> Authorize, bool AllowsAnonymous);

    /// <summary>Every action of the real API's controllers (the test project's own controllers are not part of the API).</summary>
    private IReadOnlyList<Endpoint> RealEndpoints()
    {
        var apiAssembly = typeof(ApiControllerBase).Assembly;
        return _factory.Services.GetRequiredService<EndpointDataSource>().Endpoints
            .OfType<RouteEndpoint>()
            .Where(e => e.Metadata.GetMetadata<ControllerActionDescriptor>()?.ControllerTypeInfo.Assembly == apiAssembly)
            .SelectMany(e => e.Metadata.GetMetadata<HttpMethodMetadata>()!.HttpMethods.Select(method => new Endpoint(
                method,
                e.RoutePattern.RawText!.TrimStart('/'),
                e.Metadata.GetOrderedMetadata<IAuthorizeData>(),
                e.Metadata.GetMetadata<IAllowAnonymous>() is not null)))
            .ToList();
    }

    [Fact]
    public void EveryEndpoint_HasARow_AndEveryRow_IsAnEndpoint()
    {
        var inCode = RealEndpoints().Select(e => $"{e.Method} {e.Route}").Order().ToList();
        var inTable = Table.Select(r => $"{r.Method} {r.Route}").Order().ToList();

        Assert.Equal(inTable, inCode); // a new endpoint needs a row here, and one in docs/role-audit-checklist.md
    }

    [Fact]
    public void EveryEndpoint_StatesItsAccess_AndItMatchesTheTable()
    {
        foreach (var endpoint in RealEndpoints())
        {
            var allowed = Table.Single(r => r.Method == endpoint.Method && r.Route == endpoint.Route).Allowed;
            var name = $"{endpoint.Method} {endpoint.Route}";

            if (allowed == Anyone)
            {
                Assert.True(endpoint.AllowsAnonymous, $"{name} is meant to be open, so it must say [AllowAnonymous].");
                continue;
            }

            Assert.False(endpoint.AllowsAnonymous, $"{name} must not be [AllowAnonymous].");
            Assert.NotEmpty(endpoint.Authorize); // an endpoint with no attribute at all would be open to everyone

            // Roles from every [Authorize] on the action and its controller must agree with the row; a bare [Authorize]
            // (no roles) is only right for "any signed-in user".
            var roleLists = endpoint.Authorize.Select(a => a.Roles).Where(r => !string.IsNullOrWhiteSpace(r)).ToList();
            if (allowed == AnySignedIn)
            {
                Assert.Empty(roleLists);
                continue;
            }
            Assert.NotEmpty(roleLists);
            foreach (var roles in roleLists)
                Assert.Equal(allowed.Order(), roles!.Split(',', StringSplitOptions.TrimEntries).Order());
        }
    }

    // ── each endpoint, called as each role ─────────────────────────────────────────

    public static IEnumerable<object[]> Calls() =>
        Table.SelectMany(row => new[] { "NO TOKEN" }.Concat(Roles).Select(who => new object[] { row.Method, row.Route, who }));

    [Theory]
    [MemberData(nameof(Calls))]
    public async Task EveryEndpoint_AnswersEachRole_AsTheTableSays(string method, string route, string who)
    {
        var allowed = Table.Single(r => r.Method == method && r.Route == route).Allowed;
        var client = who == "NO TOKEN" ? _factory.CreateClient() : await SignedIn(UserFor[who]);

        var status = (await Send(client, method, route)).StatusCode;

        if (allowed == Anyone)
            Assert.NotEqual(HttpStatusCode.Forbidden, status);
        else if (who == "NO TOKEN")
            Assert.Equal(HttpStatusCode.Unauthorized, status);
        else if (allowed.Contains(who))
            Assert.True(status != HttpStatusCode.Unauthorized && status != HttpStatusCode.Forbidden,
                $"{who} may call {method} {route} but was refused with {(int)status}.");
        else
            Assert.Equal(HttpStatusCode.Forbidden, status);
    }

    [Fact]
    public async Task ATokenWithNoRoleAtAll_IsRefusedByEveryRoleRestrictedEndpoint()
    {
        // Someone holding a validly signed token that names no (or an unknown) role gets nothing role-restricted.
        foreach (var role in new[] { null, "SUPERUSER", "admin" }) // the role comparison is exact: "admin" is not "ADMIN"
        {
            var client = _factory.CreateClient();
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", Tokens.For(role));

            foreach (var row in Table.Where(r => r.Allowed != Anyone && r.Allowed != AnySignedIn))
                Assert.Equal(HttpStatusCode.Forbidden, (await Send(client, row.Method, row.Route)).StatusCode);
        }
    }

    // ── the written audit ──────────────────────────────────────────────────────────

    [Fact]
    public void TheChecklistDocument_ListsEveryEndpoint_WithItsRoles()
    {
        var document = File.ReadAllLines(FindRepoFile("docs", "role-audit-checklist.md"));

        foreach (var (method, route, allowed) in Table)
        {
            var row = document.SingleOrDefault(line => line.Contains($"| `{method}` | `/{route}` |"));
            Assert.True(row is not null, $"docs/role-audit-checklist.md has no row for {method} /{route}.");
            foreach (var role in allowed.Where(r => r != "ANONYMOUS"))
                if (allowed != AnySignedIn)
                    Assert.Contains(role, row);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private async Task<HttpClient> SignedIn(string username)
    {
        var client = _factory.CreateClient();
        var login = await client.PostAsJsonAsync("/api/auth/login", new { username, password = ApiFactory.Password });
        login.EnsureSuccessStatusCode();
        var token = (await login.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("token").GetString()!;
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return client;
    }

    // Route parameters get a value that matches nothing, so an allowed caller is answered 404 or 400 (not 401/403) and no
    // call changes anything. A body is sent where the endpoint expects one; authorization runs before it is read.
    private static Task<HttpResponseMessage> Send(HttpClient client, string method, string route)
    {
        var path = "/" + route.Replace("{id}", "no-such-record");
        var request = new HttpRequestMessage(new HttpMethod(method), path);
        if (method == "POST") request.Content = new StringContent("{}", Encoding.UTF8, "application/json");
        return client.SendAsync(request);
    }

    private static string FindRepoFile(params string[] relative)
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = System.IO.Path.Combine(new[] { dir.FullName }.Concat(relative).ToArray());
            if (File.Exists(candidate)) return candidate;
        }
        throw new FileNotFoundException($"Could not find {System.IO.Path.Combine(relative)} above {AppContext.BaseDirectory}.");
    }

    /// <summary>Validly signed tokens whose role claim is whatever a test says, to prove only the exact roles count.</summary>
    private static class Tokens
    {
        public static string For(string? role)
        {
            var claims = new List<Claim> { new("sub", Guid.NewGuid().ToString()), new("name", "someone") };
            if (role is not null) claims.Add(new Claim("role", role));
            return new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
            {
                Issuer = ApiFactory.Issuer,
                Audience = ApiFactory.Audience,
                Expires = DateTime.UtcNow.AddMinutes(5),
                Subject = new ClaimsIdentity(claims),
                SigningCredentials = new SigningCredentials(
                    new SymmetricSecurityKey(Encoding.UTF8.GetBytes(ApiFactory.SigningKey)), SecurityAlgorithms.HmacSha256),
            });
        }
    }
}
