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
            // Each kind of record runs on its own, so a failure forwarding one never holds up the other.
            if (!await RunAsync("collection", sp => sp.GetRequiredService<ICboCollectionForwarder>().ForwardDueAsync(stoppingToken), stoppingToken)) break;
            if (!await RunAsync("vetting decision", sp => sp.GetRequiredService<IVettingDecisionForwarder>().ForwardDueAsync(stoppingToken), stoppingToken)) break;
        } while (await WaitAsync(timer, stoppingToken));
    }

    /// <summary>Runs one forwarder. Returns false only when the service is shutting down.</summary>
    private async Task<bool> RunAsync(string what, Func<IServiceProvider, Task<int>> forwardDue, CancellationToken stoppingToken)
    {
        try
        {
            using var scope = _scopes.CreateScope();
            var attempted = await forwardDue(scope.ServiceProvider);
            if (attempted > 0) _logger.LogInformation("Foodspace forwarding attempted {Count} {What}(s).", attempted, what);
            return true;
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            return false;
        }
        catch (Exception ex)
        {
            // E.g. the database is briefly unavailable. Next tick tries again.
            _logger.LogError(ex, "Foodspace forwarding of {What}s failed.", what);
            return true;
        }
    }

    private static async Task<bool> WaitAsync(PeriodicTimer timer, CancellationToken token)
    {
        try { return await timer.WaitForNextTickAsync(token); }
        catch (OperationCanceledException) { return false; }
    }
}
