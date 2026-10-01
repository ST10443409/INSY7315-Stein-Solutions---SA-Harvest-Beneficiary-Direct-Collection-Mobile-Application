namespace api.Services.Foodspace;

/// <summary>
/// Settings for forwarding to Foodspace, bound from the "Foodspace" configuration section.
///
/// <see cref="ApiKey"/> is a SECRET: set it with `dotnet user-secrets` or the Foodspace__ApiKey environment
/// variable, never in a committed file. <see cref="BaseUrl"/> falls back to ExternalApi:BaseUrl, which today
/// points at the Foodspace simulator (external-api-sim).
///
/// Assumed until the Foodspace team confirms their mechanism: a static API key sent in a header. If Foodspace
/// turns out to need OAuth, only the HttpClient set-up in Program.cs changes.
/// </summary>
public class FoodspaceOptions
{
    public const string SectionName = "Foodspace";

    public string? BaseUrl { get; set; }

    /// <summary>Sent in <see cref="ApiKeyHeader"/> when set. Empty means no auth header (the simulator needs none).</summary>
    public string? ApiKey { get; set; }

    public string ApiKeyHeader { get; set; } = "X-Api-Key";

    public int TimeoutSeconds { get; set; } = 15;

    /// <summary>Turn the background forwarding loop off (records stay Pending and can still be forwarded on demand).</summary>
    public bool ForwardingEnabled { get; set; } = true;

    /// <summary>How often the background loop looks for records that are due.</summary>
    public int PollIntervalSeconds { get; set; } = 15;

    public int BatchSize { get; set; } = 25;

    /// <summary>Automatic attempts before a record is left for an Admin to retry manually.</summary>
    public int MaxAttempts { get; set; } = 8;

    /// <summary>First retry delay; doubles with every failed attempt, up to <see cref="MaxDelaySeconds"/>.</summary>
    public int BaseDelaySeconds { get; set; } = 30;

    public int MaxDelaySeconds { get; set; } = 3600;

    /// <summary>
    /// Also log request payloads at Debug level. Off by default because payloads carry donor names;
    /// only turn it on locally, never in an environment with persistent logs.
    /// </summary>
    public bool LogPayloads { get; set; }
}
