using System.Text.Json.Serialization;

namespace api.DTOs;

/// <summary>
/// The standard response envelope every endpoint built from here on returns:
/// <c>{ "success": true, "data": { ... }, "error": null }</c> on success and
/// <c>{ "success": false, "data": null, "error": { "code": "...", "message": "..." } }</c> on failure.
/// Exactly one of <c>data</c> / <c>error</c> is populated. The one exception is the unhealthy health check,
/// which carries both so probes can still see which dependency is down.
/// </summary>
public class ApiResponse<T>
{
    public bool Success { get; init; }
    public T? Data { get; init; }
    public ApiError? Error { get; init; }
}

/// <summary>
/// What went wrong. <see cref="Code"/> is a stable, machine-readable UPPER_SNAKE_CASE value clients can branch on;
/// <see cref="Message"/> is safe to show to a user and never contains exception text, SQL or stack traces.
/// </summary>
public class ApiError
{
    public required string Code { get; init; }
    public required string Message { get; init; }

    /// <summary>Correlates the response with the server log entry (the request's trace identifier).</summary>
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? TraceId { get; init; }

    /// <summary>Field name to messages, only present for validation failures.</summary>
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public IDictionary<string, string[]>? Details { get; init; }
}

/// <summary>Factory helpers so envelopes are never assembled by hand.</summary>
public static class ApiResponse
{
    public static ApiResponse<T> Ok<T>(T data) => new() { Success = true, Data = data };

    public static ApiResponse<object> Fail(
        string code, string message, string? traceId = null, IDictionary<string, string[]>? details = null) =>
        new() { Success = false, Error = new ApiError { Code = code, Message = message, TraceId = traceId, Details = details } };
}

/// <summary>Error codes shared by the middleware and controllers.</summary>
public static class ApiErrorCodes
{
    public const string BadRequest = "BAD_REQUEST";
    public const string ValidationFailed = "VALIDATION_FAILED";
    public const string Unauthorized = "UNAUTHORIZED";
    public const string Forbidden = "FORBIDDEN";
    public const string NotFound = "NOT_FOUND";

    /// <summary>The record is not in a state that allows the request (e.g. retrying a record Foodspace already has).</summary>
    public const string Conflict = "CONFLICT";
    public const string Unhealthy = "SERVICE_UNAVAILABLE";

    /// <summary>Foodspace could not be reached and there is no stored copy of what was asked for.</summary>
    public const string FoodspaceUnavailable = "FOODSPACE_UNAVAILABLE";
    public const string InternalError = "INTERNAL_ERROR";
    public const string Unknown = "ERROR";

    /// <summary>The call came over plain HTTP and Security:RequireHttps is on.</summary>
    public const string HttpsRequired = "HTTPS_REQUIRED";

    /// <summary>A rate limit (e.g. sign-in attempts) was hit; the <c>Retry-After</c> header says when to try again.</summary>
    public const string TooManyRequests = "TOO_MANY_REQUESTS";

    /// <summary>Default code for an HTTP status that has no more specific one.</summary>
    public static string ForStatus(int status) => status switch
    {
        400 => BadRequest,
        401 => Unauthorized,
        403 => Forbidden,
        404 => NotFound,
        429 => TooManyRequests,
        503 => Unhealthy,
        >= 500 => InternalError,
        _ => Unknown,
    };
}
