using System.Net.Http.Json;
using System.Text.Json;
using api.Models;
using Microsoft.Extensions.Options;

namespace api.Services.Foodspace;

public enum FoodspaceOutcome
{
    /// <summary>Foodspace accepted the record.</summary>
    Success,

    /// <summary>Foodspace is down, slow, throttling or erroring: worth retrying later.</summary>
    Transient,

    /// <summary>Foodspace rejected the record itself (4xx): retrying the same data will not help.</summary>
    Permanent
}

/// <summary>The outcome of one call. <see cref="Error"/> is safe to store and show: it never contains record data.</summary>
public record FoodspaceResult(FoodspaceOutcome Outcome, string? Error = null)
{
    public static readonly FoodspaceResult Ok = new(FoodspaceOutcome.Success);
}

public interface IFoodspaceApiClient
{
    /// <summary>Sends a collection (with its product lines) to Foodspace. Never throws for Foodspace-side problems.</summary>
    Task<FoodspaceResult> SubmitCboCollectionAsync(CboCollection collection, CancellationToken cancellationToken = default);
}

/// <summary>Typed HttpClient for the Foodspace API. Base address, auth and timeout are set where it is registered.</summary>
public class FoodspaceApiClient : IFoodspaceApiClient
{
    public const string CboCollectionsPath = "api/external/cbo-collections";

    private readonly HttpClient _http;
    private readonly FoodspaceOptions _options;
    private readonly ILogger<FoodspaceApiClient> _logger;

    public FoodspaceApiClient(HttpClient http, IOptions<FoodspaceOptions> options, ILogger<FoodspaceApiClient> logger)
    {
        _http = http;
        _options = options.Value;
        _logger = logger;
    }

    public async Task<FoodspaceResult> SubmitCboCollectionAsync(CboCollection collection, CancellationToken cancellationToken = default)
    {
        var payload = FoodspaceCboCollectionMapper.ToFoodspace(collection);

        // Payloads hold donor names, so they are only logged when explicitly switched on.
        if (_options.LogPayloads && _logger.IsEnabled(LogLevel.Debug))
            _logger.LogDebug("Foodspace request for {Id}: {Payload}", collection.Id, JsonSerializer.Serialize(payload));

        try
        {
            using var response = await _http.PostAsJsonAsync(CboCollectionsPath, payload, cancellationToken);
            var status = (int)response.StatusCode;
            _logger.LogInformation("Foodspace answered {Status} for collection {Id}", status, collection.Id);

            if (response.IsSuccessStatusCode) return FoodspaceResult.Ok;

            // Status code only: the response body is never stored or logged.
            var error = $"Foodspace answered {status} ({response.ReasonPhrase})";
            return IsTransient(status)
                ? new FoodspaceResult(FoodspaceOutcome.Transient, error)
                : new FoodspaceResult(FoodspaceOutcome.Permanent, error);
        }
        catch (HttpRequestException ex)
        {
            _logger.LogWarning("Foodspace unreachable for collection {Id}: {Reason}", collection.Id, ex.Message);
            return new FoodspaceResult(FoodspaceOutcome.Transient, "Foodspace could not be reached");
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            // Not our shutdown: the HttpClient timeout fired.
            _logger.LogWarning("Foodspace timed out for collection {Id}", collection.Id);
            return new FoodspaceResult(FoodspaceOutcome.Transient, "Foodspace timed out");
        }
    }

    // 408/429 and 5xx are about Foodspace's state; 401/403 are our credentials, which an operator can fix
    // and then the record can go again, so they are retryable too. Other 4xx mean the record was rejected.
    private static bool IsTransient(int status) =>
        status is 401 or 403 or 408 or 429 || status >= 500;
}
