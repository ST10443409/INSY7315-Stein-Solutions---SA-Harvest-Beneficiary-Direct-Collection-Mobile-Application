using api.DTOs;

namespace api.Infrastructure;

/// <summary>
/// Outermost middleware: turns any unhandled exception into the standard error envelope so a failing endpoint
/// never returns a stack trace or the framework's default error page (in Development too, because this runs
/// before the developer exception page can see the exception). The full exception is logged server-side with
/// the trace id that is echoed to the client.
/// </summary>
public sealed class ExceptionHandlingMiddleware
{
    private readonly RequestDelegate _next;
    private readonly ILogger<ExceptionHandlingMiddleware> _logger;

    public ExceptionHandlingMiddleware(RequestDelegate next, ILogger<ExceptionHandlingMiddleware> logger)
    {
        _next = next;
        _logger = logger;
    }

    public async Task InvokeAsync(HttpContext context)
    {
        try
        {
            await _next(context);
        }
        catch (OperationCanceledException) when (context.RequestAborted.IsCancellationRequested)
        {
            // The client went away; there is nobody to respond to and nothing to report.
            _logger.LogDebug("Request {Path} was cancelled by the client.", context.Request.Path);
        }
        catch (Exception ex)
        {
            if (context.Response.HasStarted)
            {
                // Too late to change status or body; let the server abort the connection.
                _logger.LogError(ex, "Unhandled exception after the response started for {Path}.", context.Request.Path);
                throw;
            }

            var (status, code, message) = ex switch
            {
                // A body over the endpoint's size limit (checked after decompression, so a small gzip bomb lands here too).
                BadHttpRequestException { StatusCode: StatusCodes.Status413PayloadTooLarge } =>
                    (StatusCodes.Status413PayloadTooLarge, ApiErrorCodes.PayloadTooLarge, "The request is too large."),
                // Malformed request body / unreadable JSON etc.: the caller's fault, and the message is framework-authored.
                BadHttpRequestException bad => (bad.StatusCode, ApiErrorCodes.BadRequest, "The request could not be read."),
                _ => (StatusCodes.Status500InternalServerError, ApiErrorCodes.InternalError,
                    "An unexpected error occurred. Please try again later."),
            };

            if (status >= 500)
                _logger.LogError(ex, "Unhandled exception for {Method} {Path} (trace {TraceId}).",
                    context.Request.Method, context.Request.Path, context.TraceIdentifier);
            else
                _logger.LogWarning(ex, "Bad request for {Method} {Path} (trace {TraceId}).",
                    context.Request.Method, context.Request.Path, context.TraceIdentifier);

            context.Response.Clear();
            context.Response.StatusCode = status;
            await context.Response.WriteAsJsonAsync(ApiResponse.Fail(code, message, context.TraceIdentifier));
        }
    }
}
