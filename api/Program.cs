using api.Services;
using Microsoft.AspNetCore.Mvc;

var builder = WebApplication.CreateBuilder(args);

// Register the queue service as a singleton
builder.Services.AddSingleton<IQueueService, QueueService>();

// Register the background worker to process the queue
builder.Services.AddHostedService<QueueBackgroundWorker>();

// Register HttpClient to call external API simulator (assuming it runs on port 5001 or similar locally)
// We will set a default base address, this should ideally be in appsettings.json
builder.Services.AddHttpClient("ExternalApi", client =>
{
    // External API Sim base address (you might need to adjust the port based on your local launchSettings)
    client.BaseAddress = new Uri("http://localhost:5001");
});

var app = builder.Build();

app.MapPost("/api/sync", async ([FromBody] SyncPayload payload, IQueueService queueService, ILogger<Program> logger) =>
{
    logger.LogInformation("Received sync payload from client: {Id}", payload.Id);
    
    // Add to the background queue
    await queueService.QueueSyncPayloadAsync(payload);

    return Results.Accepted(new { Status = "Queued", ReceivedId = payload.Id });
});

app.Run();
