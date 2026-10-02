using api.DTOs;

namespace api.Infrastructure;

/// <summary>
/// Refuses any API call that did not arrive over HTTPS (Security:RequireHttps). An API is the wrong place for
/// UseHttpsRedirection: by the time a client is redirected it has already sent its bearer token in plain text, and the
/// redirect middleware silently does nothing when it cannot work out the HTTPS port (a common reverse-proxy
/// misconfiguration). Refusing instead means a misconfigured proxy shows up at once as every call failing.
///
/// 403 rather than 400 on purpose: the app treats 403 as "not the record's fault, try later", so records are never marked
/// failed because of a server misconfiguration. <c>/api/health</c> is exempt so platform probes, which call the container
/// directly over HTTP, keep working; it reveals nothing.
/// </summary>
public class RequireHttpsMiddleware
{
    private static readonly PathString HealthPath = new("/api/health");

    private readonly RequestDelegate _next;

    public RequireHttpsMiddleware(RequestDelegate next) => _next = next;

    public async Task InvokeAsync(HttpContext context)
    {
        if (context.Request.IsHttps || context.Request.Path.StartsWithSegments(HealthPath, StringComparison.OrdinalIgnoreCase))
        {
            await _next(context);
            return;
        }

        context.Response.StatusCode = StatusCodes.Status403Forbidden;
        await context.Response.WriteAsJsonAsync(ApiResponse.Fail(
            ApiErrorCodes.HttpsRequired, "This API only accepts HTTPS.", context.TraceIdentifier));
    }
}
