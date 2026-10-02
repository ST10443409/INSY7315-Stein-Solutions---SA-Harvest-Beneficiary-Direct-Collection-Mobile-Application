using System.Globalization;
using System.Text;
using System.Threading.RateLimiting;
using api.Data;
using api.DTOs;
using api.Infrastructure;
using api.Models;
using api.Options;
using api.Services;
using api.Services.Foodspace;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.HttpsPolicy;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc.ApplicationModels;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;

var builder = WebApplication.CreateBuilder(args);

// Controller-based API: endpoints live in Controllers/ and derive from ApiControllerBase, which fixes the
// `api/[controller]` route convention. The transformer kebab-cases the token (AccessDemo -> access-demo).
builder.Services.AddControllers(options =>
        options.Conventions.Add(new RouteTokenTransformerConvention(new KebabCaseParameterTransformer())))
    .AddEnvelopedValidationErrors(); // model-validation 400s use the standard { success, data, error } envelope

// OpenAPI document, served at /openapi/v1.json in Development only (see below).
builder.Services.AddOpenApi();

// PostgreSQL via EF Core. The connection string is never committed: it comes from
// `dotnet user-secrets` locally, or the ConnectionStrings__Default env var (see compose.yaml).
builder.Services.AddDbContext<AppDbContext>(options =>
    options.UseNpgsql(builder.Configuration.GetConnectionString("Default")
        ?? throw new InvalidOperationException(
            "Connection string 'ConnectionStrings:Default' is missing. See api/README.md.")));

// ── Authentication: JWT bearer ────────────────────────────────────────────────────────────
// Issuer, audience and signing key all come from configuration ("Jwt" section); the signing key is a
// secret (user-secrets / env var) and the app refuses to start without a strong one.
builder.Services.AddOptions<JwtOptions>()
    .Bind(builder.Configuration.GetSection(JwtOptions.SectionName))
    .ValidateDataAnnotations()
    .ValidateOnStart();

builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer();
builder.Services.AddOptions<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme)
    .Configure<IOptions<JwtOptions>>((bearer, jwtOptions) =>
    {
        var jwt = jwtOptions.Value;

        bearer.MapInboundClaims = false; // keep claim names as issued ("role", "sub", "name")
        bearer.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidIssuer = jwt.Issuer,
            ValidateAudience = true,
            ValidAudience = jwt.Audience,
            ValidateIssuerSigningKey = true,
            IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwt.SigningKey)),
            ValidAlgorithms = new[] { SecurityAlgorithms.HmacSha256 },
            RequireExpirationTime = true,
            ValidateLifetime = true,
            ClockSkew = TimeSpan.FromSeconds(30), // default is 5 minutes, which would blur the short expiry
            NameClaimType = "name",
            RoleClaimType = JwtTokenService.RoleClaim, // [Authorize(Roles = "...")] reads the "role" claim
        };
    });
builder.Services.AddAuthorization();

// ── Transport and abuse protection (#54) ──────────────────────────────────────────────────
// "Security" section: HTTPS is required by default and only relaxed in appsettings.Development.json.
builder.Services.AddOptions<SecurityOptions>()
    .Bind(builder.Configuration.GetSection(SecurityOptions.SectionName))
    .ValidateDataAnnotations()
    .ValidateOnStart();

// Behind a TLS-terminating proxy the app sees plain HTTP; X-Forwarded-Proto/-For restore the real scheme and client
// address, but only from the proxies listed in Security:KnownNetworks (loopback always), so a client cannot fake them.
builder.Services.AddOptions<ForwardedHeadersOptions>()
    .Configure<IOptions<SecurityOptions>>((forwarded, security) =>
    {
        forwarded.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
        foreach (var network in security.Value.KnownNetworks)
            forwarded.KnownIPNetworks.Add(System.Net.IPNetwork.Parse(network));
    });
builder.Services.AddOptions<HstsOptions>()
    .Configure<IOptions<SecurityOptions>>((hsts, security) => hsts.MaxAge = TimeSpan.FromDays(security.Value.HstsMaxAgeDays));

// Sign-in attempts are limited per client address, so passwords cannot be guessed at speed. Over the limit: 429 in the
// standard envelope, with Retry-After.
builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.OnRejected = async (context, cancellationToken) =>
    {
        var http = context.HttpContext;
        if (context.Lease.TryGetMetadata(MetadataName.RetryAfter, out var retryAfter))
            http.Response.Headers.RetryAfter = ((int)Math.Ceiling(retryAfter.TotalSeconds)).ToString(CultureInfo.InvariantCulture);
        await http.Response.WriteAsJsonAsync(
            ApiResponse.Fail(ApiErrorCodes.TooManyRequests, "Too many attempts. Wait a minute and try again.", http.TraceIdentifier),
            cancellationToken);
    };
    options.AddPolicy(SecurityOptions.LoginRateLimitPolicy, http =>
    {
        var limits = http.RequestServices.GetRequiredService<IOptions<SecurityOptions>>().Value;
        return RateLimitPartition.GetFixedWindowLimiter(
            http.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit = limits.LoginPermitLimit,
                Window = TimeSpan.FromSeconds(limits.LoginWindowSeconds),
                QueueLimit = 0,
            });
    });
});

builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddScoped<IPasswordHasher<AppUser>, PasswordHasher<AppUser>>();
builder.Services.AddScoped<IAuthService, AuthService>();
builder.Services.AddSingleton<IJwtTokenService, JwtTokenService>();

// Register the queue service as a singleton
builder.Services.AddSingleton<IQueueService, QueueService>();

// Register the background worker to process the queue
builder.Services.AddHostedService<QueueBackgroundWorker>();

// HttpClient for the external API simulator. Base URL comes from configuration
// (ExternalApi:BaseUrl) so it can be overridden per environment, e.g. in Docker.
builder.Services.AddHttpClient("ExternalApi", client =>
{
    var baseUrl = builder.Configuration["ExternalApi:BaseUrl"]
        ?? throw new InvalidOperationException("Configuration value 'ExternalApi:BaseUrl' is missing.");
    client.BaseAddress = new Uri(baseUrl);
});

// ── Foodspace forwarding ──────────────────────────────────────────────────────────────────
// Records that reached this backend are forwarded to Foodspace by a typed HttpClient. Foodspace:ApiKey is a
// secret (user-secrets / Foodspace__ApiKey); BaseUrl falls back to ExternalApi:BaseUrl (the simulator for now).
builder.Services.AddOptions<FoodspaceOptions>()
    .Bind(builder.Configuration.GetSection(FoodspaceOptions.SectionName))
    .PostConfigure(o => o.BaseUrl ??= builder.Configuration["ExternalApi:BaseUrl"]);

builder.Services.AddHttpClient<IFoodspaceApiClient, FoodspaceApiClient>((sp, client) =>
{
    var options = sp.GetRequiredService<IOptions<FoodspaceOptions>>().Value;
    client.BaseAddress = new Uri(options.BaseUrl
        ?? throw new InvalidOperationException("Configuration value 'Foodspace:BaseUrl' (or 'ExternalApi:BaseUrl') is missing."));
    client.Timeout = TimeSpan.FromSeconds(options.TimeoutSeconds);
    if (!string.IsNullOrWhiteSpace(options.ApiKey))
        client.DefaultRequestHeaders.Add(options.ApiKeyHeader, options.ApiKey);
})
// The whole beneficiary list comes back in one response; ask for it compressed.
.ConfigurePrimaryHttpMessageHandler(() => new HttpClientHandler { AutomaticDecompression = System.Net.DecompressionMethods.All });
builder.Services.AddScoped<ICboCollectionForwarder, CboCollectionForwarder>();
builder.Services.AddScoped<ICboCollectionIngestionService, CboCollectionIngestionService>();
builder.Services.AddScoped<IVettingDecisionForwarder, VettingDecisionForwarder>();
builder.Services.AddScoped<IVettingDecisionIngestionService, VettingDecisionIngestionService>();
builder.Services.AddScoped<IVettingRecordsService, VettingRecordsService>();
builder.Services.AddScoped<IAdminSyncStatusService, AdminSyncStatusService>();
builder.Services.AddScoped<IAdminSyncResolutionService, AdminSyncResolutionService>();
builder.Services.AddScoped<IAdminUserActivityService, AdminUserActivityService>();
builder.Services.AddHostedService<FoodspaceForwardingWorker>();

// Responses are compressed (gzip/brotli) when the client asks, which matters most for the vetting record pages on poor
// connections. Not enabled for HTTPS requests that reach Kestrel directly (the framework default, a BREACH precaution);
// behind a TLS-terminating host the app sees plain HTTP and compression applies.
builder.Services.AddResponseCompression();

var app = builder.Build();

// Refuse to start on a configuration that would quietly weaken a real deployment (#54); see SecurityStartupChecks.
var security = app.Services.GetRequiredService<IOptions<SecurityOptions>>().Value;
var unsafeConfiguration = SecurityStartupChecks.FindProblems(
    app.Configuration, app.Environment, security, app.Services.GetRequiredService<IOptions<FoodspaceOptions>>().Value);
if (unsafeConfiguration.Count > 0)
    throw new InvalidOperationException("Refusing to start with an unsafe configuration:\n- " + string.Join("\n- ", unsafeConfiguration));
if (security.RequireHttps)
    app.Logger.LogInformation("HTTPS is required: plain-HTTP API calls are refused.");
else if (!app.Environment.IsDevelopment())
    app.Logger.LogWarning("Security:RequireHttps is off in {Environment}: API calls are accepted over plain HTTP.", app.Environment.EnvironmentName);

// First, so everything below sees the client's real scheme and address.
app.UseForwardedHeaders();

app.UseResponseCompression();

// Opt-in (Database:MigrateOnStartup=true, set in compose.yaml): apply pending migrations at start-up.
if (app.Configuration.GetValue<bool>("Database:MigrateOnStartup"))
{
    using var scope = app.Services.CreateScope();
    scope.ServiceProvider.GetRequiredService<AppDbContext>().Database.Migrate();
}

// Opt-in (Seed:Enabled=true, local dev only): one test user per role.
if (app.Configuration.GetValue<bool>("Seed:Enabled"))
{
    using var scope = app.Services.CreateScope();
    await TestUserSeeder.SeedAsync(
        scope.ServiceProvider,
        app.Configuration["Seed:TestUserPassword"],
        app.Services.GetRequiredService<ILoggerFactory>().CreateLogger("TestUserSeeder"));
}

// Must stay first: catches whatever anything below it throws and returns the standard error envelope.
app.UseMiddleware<ExceptionHandlingMiddleware>();
app.UseEnvelopedStatusCodes(); // bodyless 401/403/404 etc. get the envelope too

if (security.RequireHttps)
{
    app.UseHsts();
    app.UseMiddleware<RequireHttpsMiddleware>(); // refuse, never redirect: see the middleware for why
}

app.UseRateLimiter();

app.UseAuthentication();
app.UseAuthorization();

app.MapControllers();

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi(); // GET /openapi/v1.json
}

app.Run();

// Lets the integration tests (WebApplicationFactory<Program>) reach the top-level Program class.
public partial class Program { }
