namespace api.DTOs;

/// <summary>
/// Payload of GET /api/health. Deliberately minimal: "ok" or "unhealthy" plus a per-dependency verdict and the build
/// revision, and nothing else (no connection strings, host names, library versions or exception text). The revision is the
/// first 12 characters of the git commit the container image was built from ("unknown" outside the pipeline's builds): it is
/// what lets a deployment prove the new image is the one answering. A commit prefix of a private repository is not a secret.
/// </summary>
public record HealthStatus(string Status, string Database, DateTimeOffset Timestamp, string Revision);
