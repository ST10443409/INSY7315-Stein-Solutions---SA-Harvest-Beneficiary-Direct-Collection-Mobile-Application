namespace api.DTOs;

/// <summary>
/// Payload of GET /api/health. Deliberately minimal: "ok" or "unhealthy" plus a per-dependency verdict, and
/// nothing else (no connection strings, host names, versions or exception text).
/// </summary>
public record HealthStatus(string Status, string Database, DateTimeOffset Timestamp);
