using System.Text;
using System.Text.Json;

namespace api.Services;

public class QueueBackgroundWorker : BackgroundService
{
    private readonly IQueueService _queueService;
    private readonly ILogger<QueueBackgroundWorker> _logger;
    private readonly IHttpClientFactory _httpClientFactory;

    public QueueBackgroundWorker(
        IQueueService queueService, 
        ILogger<QueueBackgroundWorker> logger,
        IHttpClientFactory httpClientFactory)
    {
        _queueService = queueService;
        _logger = logger;
        _httpClientFactory = httpClientFactory;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("Queue Background Worker is starting.");

        await foreach (var payload in _queueService.DequeueAsync(stoppingToken))
        {
            try
            {
                _logger.LogInformation("Processing payload: {Id}", payload.Id);
                
                // Call external-api-sim
                var client = _httpClientFactory.CreateClient("ExternalApi");
                var json = JsonSerializer.Serialize(payload);
                var content = new StringContent(json, Encoding.UTF8, "application/json");

                var response = await client.PostAsync("/api/external/sync", content, stoppingToken);
                
                if (response.IsSuccessStatusCode)
                {
                    _logger.LogInformation("Successfully sent payload {Id} to external API.", payload.Id);
                }
                else
                {
                    _logger.LogWarning("Failed to send payload {Id}. Status: {StatusCode}", payload.Id, response.StatusCode);
                    // Depending on requirements, we could re-queue the payload here.
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error processing payload {Id}", payload.Id);
            }
        }
    }
}
