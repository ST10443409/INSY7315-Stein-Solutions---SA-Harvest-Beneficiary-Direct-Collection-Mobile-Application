using api.Models;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace api.Controllers;

/// <summary>
/// PLACEHOLDER demonstrating the authorization pattern later controllers follow (#36, #43, #47, #49-#51):
/// [Authorize] for "any signed-in user" and [Authorize(Roles = ...)] for role-scoped endpoints.
/// Delete once real endpoints use the pattern.
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
