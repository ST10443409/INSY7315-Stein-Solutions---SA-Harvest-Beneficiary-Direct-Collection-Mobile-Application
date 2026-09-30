using api.DTOs;
using Microsoft.AspNetCore.Mvc;

namespace api.Infrastructure;

/// <summary>Wiring that makes framework-generated error responses use the standard envelope.</summary>
public static class ApiEnvelopeSetup
{
    /// <summary>[ApiController] model-validation failures (400) become <c>VALIDATION_FAILED</c> envelopes with per-field details.</summary>
    public static IMvcBuilder AddEnvelopedValidationErrors(this IMvcBuilder builder) =>
        builder.ConfigureApiBehaviorOptions(options =>
            options.InvalidModelStateResponseFactory = context =>
            {
                var details = context.ModelState
                    .Where(e => e.Value is { Errors.Count: > 0 })
                    .ToDictionary(
                        e => e.Key,
                        e => e.Value!.Errors
                            .Select(err => string.IsNullOrEmpty(err.ErrorMessage) ? "Invalid value." : err.ErrorMessage)
                            .ToArray());

                return new BadRequestObjectResult(ApiResponse.Fail(
                    ApiErrorCodes.ValidationFailed,
                    "One or more fields are invalid.",
                    context.HttpContext.TraceIdentifier,
                    details));
            });

    /// <summary>
    /// Bodyless 4xx/5xx responses (a bare 401 from the JWT handler, a 403 from a role check, an unknown route's 404)
    /// get an envelope too. Responses that already carry a body are left alone.
    /// </summary>
    public static IApplicationBuilder UseEnvelopedStatusCodes(this IApplicationBuilder app) =>
        app.UseStatusCodePages(async context =>
        {
            var http = context.HttpContext;
            var status = http.Response.StatusCode;
            var message = status switch
            {
                StatusCodes.Status401Unauthorized => "Authentication is required.",
                StatusCodes.Status403Forbidden => "You do not have permission to do that.",
                StatusCodes.Status404NotFound => "The requested resource was not found.",
                _ => "The request could not be completed.",
            };

            await http.Response.WriteAsJsonAsync(
                ApiResponse.Fail(ApiErrorCodes.ForStatus(status), message, http.TraceIdentifier));
        });
}
