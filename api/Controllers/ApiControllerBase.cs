using api.DTOs;
using Microsoft.AspNetCore.Mvc;

namespace api.Controllers;

/// <summary>
/// Base class for every controller.
///
/// Route convention: <c>api/[controller]</c>, so <c>OrdersController</c> serves <c>/api/orders</c>. The
/// <c>[controller]</c> token is kebab-cased (<c>AccessDemoController</c> -> <c>/api/access-demo</c>), see
/// <see cref="api.Infrastructure.KebabCaseParameterTransformer"/>. Derive from this class and add only the
/// action-level templates (<c>[HttpGet("{id}")]</c>); do not declare a controller-level <c>[Route]</c>.
/// <c>api.Tests</c> fails if a controller does not follow this.
///
/// Responses: return <see cref="Success{T}"/> / <see cref="Failure"/> so callers always get the
/// <see cref="ApiResponse{T}"/> envelope. Unhandled exceptions, model-validation errors and empty
/// 401/403/404 responses are turned into the same envelope by the pipeline.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
public abstract class ApiControllerBase : ControllerBase
{
    /// <summary>200 with <c>{ success: true, data }</c>.</summary>
    protected ActionResult<ApiResponse<T>> Success<T>(T data) => Ok(ApiResponse.Ok(data));

    /// <summary>The given status with <c>{ success: false, error: { code, message } }</c>.</summary>
    protected ObjectResult Failure(int statusCode, string code, string message) =>
        StatusCode(statusCode, ApiResponse.Fail(code, message, HttpContext.TraceIdentifier));
}
