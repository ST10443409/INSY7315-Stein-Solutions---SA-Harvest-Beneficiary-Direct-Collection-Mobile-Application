using Microsoft.AspNetCore.Mvc;

var builder = WebApplication.CreateBuilder(args);
var app = builder.Build();

app.MapPost("/api/external/sync", ([FromBody] SyncPayload payload, ILogger<Program> logger) =>
{
    logger.LogInformation("External API Simulator received payload with ID: {Id} at {Time}", payload.Id, DateTime.UtcNow);
    
    // Simulate some processing delay
    Thread.Sleep(500);

    return Results.Ok(new { Status = "Success", ReceivedId = payload.Id });
});

app.MapGet("/api/external/status", () => Results.Ok(new { Status = "Online" }));

app.Run();

public class SyncPayload
{
    public string Id { get; set; } = string.Empty;
    public string Data { get; set; } = string.Empty;
}
