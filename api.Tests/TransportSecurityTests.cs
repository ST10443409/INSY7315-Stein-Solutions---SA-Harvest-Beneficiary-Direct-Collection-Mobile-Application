using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using api.Infrastructure;
using api.Options;
using api.Services.Foodspace;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace api.Tests;

/// <summary>
/// The API as it runs outside Development (#54): HTTPS is required (never redirected to), HSTS is sent, a TLS-terminating
/// proxy is believed only when it is a known one, and the app refuses to start on configurations that would quietly
/// weaken that.
/// </summary>
public class TransportSecurityTests : IClassFixture<TransportSecurityTests.ProductionApiFactory>
{
    private const string TrustedProxy = "10.1.2.3";
    private const string Stranger = "203.0.113.9";

    private readonly ProductionApiFactory _factory;

    public TransportSecurityTests(ProductionApiFactory factory) => _factory = factory;

    /// <summary>
    /// Production settings that pass the start-up checks: Foodspace over HTTPS, the database over TLS, one known proxy
    /// network. Requests can claim to come from any address via <see cref="RemoteAddressHeader"/>.
    /// </summary>
    public sealed class ProductionApiFactory : ApiFactory
    {
        public const string RemoteAddressHeader = "X-Test-Remote-Address";

        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            base.ConfigureWebHost(builder);
            builder.UseEnvironment("Production");
            builder.UseSetting("Foodspace:BaseUrl", "https://foodspace.example.test/");
            builder.UseSetting("ConnectionStrings:Default", "Host=db.example.test;Database=saharvest;Username=app;Password=x;SSL Mode=VerifyFull");
            builder.UseSetting("Security:KnownNetworks:0", "10.0.0.0/8");
            builder.ConfigureServices(services => services.AddTransient<IStartupFilter, RemoteAddressFromHeader>());
        }

        // TestServer connections have no remote address; this stands in for the network so proxy trust can be tested.
        private sealed class RemoteAddressFromHeader : IStartupFilter
        {
            public Action<IApplicationBuilder> Configure(Action<IApplicationBuilder> next) => app =>
            {
                app.Use(async (context, nextMiddleware) =>
                {
                    if (context.Request.Headers.TryGetValue(RemoteAddressHeader, out var address))
                        context.Connection.RemoteIpAddress = IPAddress.Parse(address.ToString());
                    await nextMiddleware(context);
                });
                next(app);
            };
        }
    }

    private HttpClient Client(string scheme) =>
        _factory.CreateClient(new() { BaseAddress = new Uri($"{scheme}://api.saharvest.example.test") });

    private static async Task<string?> ErrorCode(HttpResponseMessage response) =>
        (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("error").GetProperty("code").GetString();

    // ── HTTPS required ─────────────────────────────────────────────────────────────

    [Fact]
    public async Task APlainHttpCall_IsRefused_NotRedirected()
    {
        var response = await Client("http").PostAsJsonAsync("/api/auth/login",
            new { username = "cbo_test_user", password = ApiFactory.Password });

        // 403 (not a redirect: the credentials have already crossed the network by then), so the app also treats it as
        // "not the record's fault" and never marks records failed because of it.
        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Null(response.Headers.Location);
        Assert.Equal("HTTPS_REQUIRED", await ErrorCode(response));
    }

    [Fact]
    public async Task AnHttpsCall_IsServed_WithHsts()
    {
        var response = await Client("https").PostAsJsonAsync("/api/auth/login",
            new { username = "cbo_test_user", password = ApiFactory.Password });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(response.Headers.TryGetValues("Strict-Transport-Security", out var hsts));
        Assert.Equal("max-age=31536000", Assert.Single(hsts));
    }

    [Fact]
    public async Task TheHealthProbe_StillAnswersOverPlainHttp()
    {
        // Platform probes call the container directly, without TLS.
        var response = await Client("http").GetAsync("/api/health");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    // ── TLS-terminating proxy ──────────────────────────────────────────────────────

    private async Task<HttpResponseMessage> MeViaProxy(string from)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, "/api/auth/me");
        request.Headers.Add(ProductionApiFactory.RemoteAddressHeader, from);
        request.Headers.Add("X-Forwarded-Proto", "https");
        return await Client("http").SendAsync(request);
    }

    [Fact]
    public async Task AKnownProxy_SayingTheClientUsedHttps_IsBelieved()
    {
        var response = await MeViaProxy(TrustedProxy);

        // Past the HTTPS check; no token, so authentication answers.
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task AnyoneElse_ClaimingHttps_IsNotBelieved()
    {
        var response = await MeViaProxy(Stranger);

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Equal("HTTPS_REQUIRED", await ErrorCode(response));
    }

    // ── refusing to start ──────────────────────────────────────────────────────────

    [Fact]
    public void TestAccounts_OutsideDevelopment_StopTheAppStarting()
    {
        using var seeded = _factory.WithWebHostBuilder(b =>
        {
            b.UseSetting("Seed:Enabled", "true");
            b.UseSetting("Seed:TestUserPassword", "irrelevant-test-value");
        });

        var error = Assert.Throws<InvalidOperationException>(() => seeded.CreateClient());
        Assert.Contains("Seed:Enabled", error.Message);
    }

    private static IConfiguration Config(params (string Key, string? Value)[] values) =>
        new ConfigurationBuilder().AddInMemoryCollection(values.Select(v => new KeyValuePair<string, string?>(v.Key, v.Value))).Build();

    private static IHostEnvironment Env(string name) => new FakeEnvironment { EnvironmentName = name };

    private sealed class FakeEnvironment : IHostEnvironment
    {
        public string EnvironmentName { get; set; } = "Production";
        public string ApplicationName { get; set; } = "api";
        public string ContentRootPath { get; set; } = ".";
        public Microsoft.Extensions.FileProviders.IFileProvider ContentRootFileProvider { get; set; } = null!;
    }

    private static readonly FoodspaceOptions HttpsFoodspace = new() { BaseUrl = "https://foodspace.example.test/" };
    private const string EncryptedDb = "Host=db.example.test;Database=x;Username=x;Password=x;SSL Mode=VerifyFull";

    [Fact]
    public void ACorrectProductionConfiguration_HasNoProblems()
    {
        var problems = SecurityStartupChecks.FindProblems(
            Config(("ConnectionStrings:Default", EncryptedDb)), Env("Production"), new SecurityOptions(), HttpsFoodspace);

        Assert.Empty(problems);
    }

    [Fact]
    public void SeedingTestUsers_IsOnlyAllowedInDevelopment()
    {
        var config = Config(("Seed:Enabled", "true"), ("ConnectionStrings:Default", EncryptedDb));

        Assert.Single(SecurityStartupChecks.FindProblems(config, Env("Production"), new SecurityOptions(), HttpsFoodspace));
        Assert.Single(SecurityStartupChecks.FindProblems(config, Env("Staging"), new SecurityOptions(), HttpsFoodspace));
        Assert.Empty(SecurityStartupChecks.FindProblems(config, Env("Development"), new SecurityOptions(), HttpsFoodspace));
    }

    [Theory]
    [InlineData("http://foodspace.example.test/")]
    [InlineData(null)]
    public void WithHttpsRequired_FoodspaceMustBeHttps(string? baseUrl)
    {
        var problems = SecurityStartupChecks.FindProblems(
            Config(("ConnectionStrings:Default", EncryptedDb)), Env("Production"), new SecurityOptions(), new FoodspaceOptions { BaseUrl = baseUrl });

        Assert.Contains(problems, p => p.StartsWith("Foodspace:BaseUrl"));
    }

    [Theory]
    [InlineData("Host=db.example.test;Database=x;Username=x;Password=x", false)] // Npgsql's default (Prefer) silently falls back to plain text
    [InlineData("Host=db.example.test;Database=x;Username=x;Password=x;SSL Mode=Disable", false)]
    [InlineData("Host=db.example.test;Database=x;Username=x;Password=x;SSL Mode=Require", true)]
    [InlineData("Host=localhost;Database=x;Username=x;Password=x", true)] // never crosses a network
    [InlineData("Host=/var/run/postgresql;Database=x;Username=x", true)]
    public void WithHttpsRequired_TheDatabaseConnectionMustBeEncrypted(string connectionString, bool ok)
    {
        var problems = SecurityStartupChecks.FindProblems(
            Config(("ConnectionStrings:Default", connectionString)), Env("Production"), new SecurityOptions(), HttpsFoodspace);

        Assert.Equal(ok, !problems.Any(p => p.StartsWith("ConnectionStrings:Default")));
    }

    [Fact]
    public void WithHttpsNotRequired_TheOnwardHopsAreNotChecked()
    {
        var problems = SecurityStartupChecks.FindProblems(
            Config(("ConnectionStrings:Default", "Host=db;Database=x")), Env("Development"),
            new SecurityOptions { RequireHttps = false }, new FoodspaceOptions { BaseUrl = "http://external-api-sim:8080" });

        Assert.Empty(problems);
    }
}
