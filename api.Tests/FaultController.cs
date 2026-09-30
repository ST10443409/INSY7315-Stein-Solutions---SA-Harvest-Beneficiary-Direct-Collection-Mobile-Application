using api.Controllers;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace api.Tests;

/// <summary>Test-only endpoint (registered by <see cref="ApiFactory"/>, never part of the real API) that always fails.</summary>
public class FaultController : ApiControllerBase
{
    public const string SecretDetail = "secret-internal-detail: SELECT * FROM users WHERE password = 'hunter2'";

    [HttpGet("throw")]
    [AllowAnonymous]
    public IActionResult Throw() => throw new InvalidOperationException(SecretDetail);
}
