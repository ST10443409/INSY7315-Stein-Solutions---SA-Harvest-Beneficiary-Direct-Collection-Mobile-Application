using api.Models;
using api.Services;
using Microsoft.AspNetCore.Mvc;

namespace api.Controllers;

[ApiController]
[Route("api/sync")]
public class SyncController : ControllerBase
{
    private readonly IQueueService _queueService;
    private readonly ILogger<SyncController> _logger;

    public SyncController(IQueueService queueService, ILogger<SyncController> logger)
    {
        _queueService = queueService;
        _logger = logger;
    }

    [HttpPost]
    public async Task<IActionResult> Post([FromBody] SyncPayload payload)
    {
        _logger.LogInformation("Received sync payload from client: {Id}", payload.Id);

        // Add to the background queue
        await _queueService.QueueSyncPayloadAsync(payload);

        return Accepted(new { Status = "Queued", ReceivedId = payload.Id });
    }
}
