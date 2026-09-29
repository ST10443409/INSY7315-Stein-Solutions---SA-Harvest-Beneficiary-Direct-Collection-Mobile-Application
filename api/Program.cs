using api.Services;

var builder = WebApplication.CreateBuilder(args);

// Controller-based API: endpoints live in Controllers/.
builder.Services.AddControllers();

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

app.MapControllers();

app.Run();
