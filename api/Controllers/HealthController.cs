using api.Data;
using api.DTOs;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace api.Controllers;

/// <summary>
/// Liveness/readiness probe for CI smoke tests, load-balancer probes and the Admin sync-monitoring view.
/// Anonymous by design, so it must reveal nothing sensitive, even when the database is down.
/// </summary>
public class HealthController : ApiControllerBase
{
    /// <summary>How long the database check may take before the service is reported unhealthy.</summary>
    private static readonly TimeSpan DatabaseTimeout = TimeSpan.FromSeconds(3);

    private readonly AppDbContext _db;
    private readonly TimeProvider _time;
    private readonly ILogger<HealthController> _logger;
    private readonly string _revision;

    public HealthController(AppDbContext db, TimeProvider time, ILogger<HealthController> logger, IConfiguration configuration)
    {
        _db = db;
        _time = time;
        _logger = logger;
        // Set by the Dockerfile from the pipeline's build argument; "unknown" for a local or hand-built image.
        _revision = string.IsNullOrWhiteSpace(configuration["BUILD_REVISION"]) ? "unknown" : configuration["BUILD_REVISION"]!;
    }

    /// <summary>200 when the API and its database are reachable, 503 when the database is not.</summary>
    [HttpGet]
    [AllowAnonymous]
    [ProducesResponseType<ApiResponse<HealthStatus>>(StatusCodes.Status200OK)]
    [ProducesResponseType<ApiResponse<HealthStatus>>(StatusCodes.Status503ServiceUnavailable)]
    public async Task<IActionResult> Get(CancellationToken cancellationToken)
    {
        var databaseUp = await CanReachDatabaseAsync(cancellationToken);
        var health = new HealthStatus(
            databaseUp ? "ok" : "unhealthy",
            databaseUp ? "ok" : "unavailable",
            _time.GetUtcNow(),
            _revision);

        if (databaseUp)
            return Ok(ApiResponse.Ok(health));

        return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiResponse<HealthStatus>
        {
            Success = false,
            Data = health,
            Error = new ApiError
            {
                Code = ApiErrorCodes.Unhealthy,
                Message = "The service is running but a dependency is unavailable.",
                TraceId = HttpContext.TraceIdentifier,
            },
        });
    }

    private async Task<bool> CanReachDatabaseAsync(CancellationToken requestAborted)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(requestAborted);
        timeout.CancelAfter(DatabaseTimeout);
        try
        {
            return await _db.Database.CanConnectAsync(timeout.Token);
        }
        catch (Exception ex) when (!requestAborted.IsCancellationRequested)
        {
            // Logged for operators; never surfaced to the (anonymous) caller.
            _logger.LogWarning(ex, "Health check: database is not reachable.");
            return false;
        }
    }
}
