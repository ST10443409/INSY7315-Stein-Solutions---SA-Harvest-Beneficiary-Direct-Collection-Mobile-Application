using api.Controllers;
using api.Models;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace api.Tests;

/// <summary>
/// Test-only endpoints (registered by <see cref="ApiFactory"/>, never part of the real API) that show each way of
/// restricting access: [Authorize] for "any signed-in user" and [Authorize(Roles = ...)] for role-scoped endpoints.
/// <see cref="AuthEndpointTests"/> uses them to prove how tokens and roles are enforced without depending on a real
/// endpoint's behaviour. They used to ship in the API as a placeholder; the role audit (#52) moved them here so the
/// deployed surface is only real endpoints.
/// </summary>
[Authorize] // every action below requires a valid token; the role attributes narrow it further
public class AccessDemoController : ApiControllerBase
{
    [HttpGet("any")]
    public IActionResult AnyAuthenticatedUser() => Ok(new { access = "any authenticated user" });

    [HttpGet("cbo")]
    [Authorize(Roles = AppRoles.CboCollection)]
    public IActionResult CboCollectionOnly() => Ok(new { access = AppRoles.CboCollection });

    [HttpGet("vetting")]
    [Authorize(Roles = AppRoles.Vetting)]
    public IActionResult VettingOnly() => Ok(new { access = AppRoles.Vetting });

    [HttpGet("admin")]
    [Authorize(Roles = AppRoles.Admin)]
    public IActionResult AdminOnly() => Ok(new { access = AppRoles.Admin });

    // Comma-separated roles mean "any of".
    [HttpGet("vetting-or-admin")]
    [Authorize(Roles = $"{AppRoles.Vetting},{AppRoles.Admin}")]
    public IActionResult VettingOrAdmin() => Ok(new { access = "VETTING or ADMIN" });
}
