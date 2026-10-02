using System.Net;
using System.Net.Http.Json;
using System.Reflection;
using System.Text;
using System.Text.Json;
using api.Controllers;
using api.Data;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace api.Tests;

public class HealthAndEnvelopeTests : IClassFixture<ApiFactory>
{
    private readonly ApiFactory _factory;

    public HealthAndEnvelopeTests(ApiFactory factory) => _factory = factory;

    private static void AssertErrorEnvelope(JsonElement body, string expectedCode)
    {
        Assert.False(body.GetProperty("success").GetBoolean());
        Assert.Equal(JsonValueKind.Null, body.GetProperty("data").ValueKind);
        Assert.Equal(expectedCode, body.GetProperty("error").GetProperty("code").GetString());
        Assert.False(string.IsNullOrWhiteSpace(body.GetProperty("error").GetProperty("message").GetString()));
    }

    // ── health ─────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task Health_IsAnonymous_Returns200_WithTheEnvelope()
    {
        var response = await _factory.CreateClient().GetAsync("/api/health"); // no Authorization header

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("application/json", response.Content.Headers.ContentType?.MediaType);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.True(body.GetProperty("success").GetBoolean());
        Assert.Equal(JsonValueKind.Null, body.GetProperty("error").ValueKind);
        var data = body.GetProperty("data");
        Assert.Equal("ok", data.GetProperty("status").GetString());
        Assert.Equal("ok", data.GetProperty("database").GetString());
        Assert.True(data.TryGetProperty("timestamp", out _));
    }

    [Fact]
    public async Task Health_WhenTheDatabaseIsDown_Is503_AndLeaksNothing()
    {
        using var factory = new UnreachableDatabaseFactory();

        var response = await factory.CreateClient().GetAsync("/api/health");
        var raw = await response.Content.ReadAsStringAsync();

        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
        var body = JsonDocument.Parse(raw).RootElement;
        Assert.False(body.GetProperty("success").GetBoolean());
        Assert.Equal("unhealthy", body.GetProperty("data").GetProperty("status").GetString());
        Assert.Equal("unavailable", body.GetProperty("data").GetProperty("database").GetString());
        Assert.Equal("SERVICE_UNAVAILABLE", body.GetProperty("error").GetProperty("code").GetString());
        foreach (var secret in new[] { UnreachableDatabaseFactory.DbPassword, UnreachableDatabaseFactory.DbUser, "127.0.0.1", "Npgsql", "Host=", "   at " })
            Assert.DoesNotContain(secret, raw, StringComparison.OrdinalIgnoreCase);
    }

    // ── routing convention ─────────────────────────────────────────────────────────

    [Fact]
    public void EveryController_DerivesFromApiControllerBase_AndDoesNotDeclareItsOwnRoute()
    {
        var controllers = typeof(ApiControllerBase).Assembly.GetTypes()
            .Where(t => !t.IsAbstract && typeof(ControllerBase).IsAssignableFrom(t))
            .ToList();

        Assert.NotEmpty(controllers);
        foreach (var controller in controllers)
        {
            Assert.True(typeof(ApiControllerBase).IsAssignableFrom(controller), $"{controller.Name} must derive from ApiControllerBase.");
            Assert.Empty(controller.GetCustomAttributes<RouteAttribute>(inherit: false));
        }
    }

    [Theory]
    [InlineData("/api/auth/me", HttpStatusCode.Unauthorized)]         // AuthController       -> api/auth
    [InlineData("/api/access-demo/any", HttpStatusCode.Unauthorized)] // AccessDemoController -> api/access-demo (kebab-case)
    [InlineData("/api/accessdemo/any", HttpStatusCode.NotFound)]      // the un-kebabed form must not exist
    [InlineData("/api/health", HttpStatusCode.OK)]
    public async Task Controllers_AreServedUnderApiControllerName(string path, HttpStatusCode expected)
    {
        Assert.Equal(expected, (await _factory.CreateClient().GetAsync(path)).StatusCode);
    }

    // ── error envelope ─────────────────────────────────────────────────────────────

    [Fact]
    public async Task UnhandledException_Returns500Envelope_WithoutStackTraceOrExceptionText()
    {
        var response = await _factory.CreateClient().GetAsync("/api/fault/throw");
        var raw = await response.Content.ReadAsStringAsync();

        Assert.Equal(HttpStatusCode.InternalServerError, response.StatusCode);
        Assert.Equal("application/json", response.Content.Headers.ContentType?.MediaType);
        var body = JsonDocument.Parse(raw).RootElement;
        AssertErrorEnvelope(body, "INTERNAL_ERROR");
        Assert.False(string.IsNullOrEmpty(body.GetProperty("error").GetProperty("traceId").GetString()));
        foreach (var leak in new[] { FaultController.SecretDetail, "hunter2", "InvalidOperationException", "   at ", "FaultController", ".cs" })
            Assert.DoesNotContain(leak, raw);
    }

    [Fact]
    public async Task UnauthenticatedAndUnknownRoutes_UseTheEnvelope()
    {
        var client = _factory.CreateClient();

        var unauthorized = await client.GetAsync("/api/auth/me");
        Assert.Equal(HttpStatusCode.Unauthorized, unauthorized.StatusCode);
        AssertErrorEnvelope(await unauthorized.Content.ReadFromJsonAsync<JsonElement>(), "UNAUTHORIZED");

        var missing = await client.GetAsync("/api/does-not-exist");
        Assert.Equal(HttpStatusCode.NotFound, missing.StatusCode);
        AssertErrorEnvelope(await missing.Content.ReadFromJsonAsync<JsonElement>(), "NOT_FOUND");
    }

    [Fact]
    public async Task ModelValidationFailure_Is400Envelope_WithFieldDetails()
    {
        var response = await _factory.CreateClient().PostAsJsonAsync("/api/auth/login", new { username = "", password = "x" });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        AssertErrorEnvelope(body, "VALIDATION_FAILED");
        Assert.True(body.GetProperty("error").GetProperty("details").TryGetProperty("Username", out _));
    }

    [Fact]
    public async Task MalformedJsonBody_Is400Envelope()
    {
        var response = await _factory.CreateClient().PostAsync(
            "/api/auth/login", new StringContent("{ not json", Encoding.UTF8, "application/json"));

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        AssertErrorEnvelope(await response.Content.ReadFromJsonAsync<JsonElement>(), "VALIDATION_FAILED");
    }

    // ── OpenAPI ────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task OpenApiDocument_IsServedInDevelopment_AndListsTheHealthRoute()
    {
        var response = await _factory.CreateClient().GetAsync("/openapi/v1.json");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var paths = (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("paths");
        Assert.True(paths.TryGetProperty("/api/health", out _));
        Assert.True(paths.TryGetProperty("/api/auth/login", out _));
        Assert.True(paths.TryGetProperty("/api/cbo-collection/sync", out _));
    }

    [Fact]
    public async Task OpenApiDocument_IsNotServedOutsideDevelopment()
    {
        // Production also requires HTTPS and a safe configuration (#54), so it needs the production-like factory.
        using var production = new TransportSecurityTests.ProductionApiFactory();
        var client = production.CreateClient(new() { BaseAddress = new Uri("https://localhost") });

        Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync("/openapi/v1.json")).StatusCode);
    }

    /// <summary>Points the API at a Postgres that refuses connections, with recognisable credentials to look for in leaks.</summary>
    private sealed class UnreachableDatabaseFactory : ApiFactory
    {
        public const string DbUser = "leaky_db_user";
        public const string DbPassword = "leaky-db-password";

        protected override bool SeedUsers => false;

        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            base.ConfigureWebHost(builder);
            builder.ConfigureServices(services =>
            {
                services.RemoveAll<DbContextOptions<AppDbContext>>();
                services.RemoveAll<AppDbContext>();
                foreach (var d in services.Where(d => d.ServiceType.IsGenericType
                             && d.ServiceType.GetGenericArguments().Contains(typeof(AppDbContext))).ToList())
                    services.Remove(d);
                services.AddDbContext<AppDbContext>(o => o.UseNpgsql(
                    $"Host=127.0.0.1;Port=1;Database=x;Username={DbUser};Password={DbPassword};Timeout=2;Command Timeout=2"));
            });
        }
    }
}
