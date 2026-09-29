using api.Data;
using api.Services;
using Microsoft.EntityFrameworkCore;

var builder = WebApplication.CreateBuilder(args);

// Controller-based API: endpoints live in Controllers/.
builder.Services.AddControllers();

// PostgreSQL via EF Core. The connection string is never committed: it comes from
// `dotnet user-secrets` locally, or the ConnectionStrings__Default env var (see compose.yaml).
builder.Services.AddDbContext<AppDbContext>(options =>
    options.UseNpgsql(builder.Configuration.GetConnectionString("Default")
        ?? throw new InvalidOperationException(
            "Connection string 'ConnectionStrings:Default' is missing. See api/README.md.")));

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

var app = builder.Build();

// Opt-in (Database:MigrateOnStartup=true, set in compose.yaml): apply pending migrations at start-up.
if (app.Configuration.GetValue<bool>("Database:MigrateOnStartup"))
{
    using var scope = app.Services.CreateScope();
    scope.ServiceProvider.GetRequiredService<AppDbContext>().Database.Migrate();
}

app.MapControllers();

app.Run();
