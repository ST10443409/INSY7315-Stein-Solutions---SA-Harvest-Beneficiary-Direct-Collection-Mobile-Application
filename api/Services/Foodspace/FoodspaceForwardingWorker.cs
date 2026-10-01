using Microsoft.Extensions.Options;

namespace api.Services.Foodspace;

/// <summary>
/// Background loop that forwards collections to Foodspace and retries the ones Foodspace did not accept,
/// so a transient Foodspace outage heals by itself with no manual intervention.
/// </summary>
public class FoodspaceForwardingWorker : BackgroundService
{
    private readonly IServiceScopeFactory _scopes;
    private readonly FoodspaceOptions _options;
    private readonly ILogger<FoodspaceForwardingWorker> _logger;

    public FoodspaceForwardingWorker(
        IServiceScopeFactory scopes, IOptions<FoodspaceOptions> options, ILogger<FoodspaceForwardingWorker> logger)
    {
        _scopes = scopes;
        _options = options.Value;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!_options.ForwardingEnabled)
        {
            _logger.LogInformation("Foodspace forwarding loop is disabled (Foodspace:ForwardingEnabled=false).");
            return;
        }

        var interval = TimeSpan.FromSeconds(Math.Max(1, _options.PollIntervalSeconds));
        using var timer = new PeriodicTimer(interval);
        do
        {
            try
            {
                using var scope = _scopes.CreateScope();
                var forwarder = scope.ServiceProvider.GetRequiredService<ICboCollectionForwarder>();
                var attempted = await forwarder.ForwardDueAsync(stoppingToken);
                if (attempted > 0) _logger.LogInformation("Foodspace forwarding attempted {Count} collection(s).", attempted);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                // E.g. the database is briefly unavailable. Next tick tries again.
                _logger.LogError(ex, "Foodspace forwarding run failed.");
            }
        } while (await WaitAsync(timer, stoppingToken));
    }

    private static async Task<bool> WaitAsync(PeriodicTimer timer, CancellationToken token)
    {
        try { return await timer.WaitForNextTickAsync(token); }
        catch (OperationCanceledException) { return false; }
    }
}
