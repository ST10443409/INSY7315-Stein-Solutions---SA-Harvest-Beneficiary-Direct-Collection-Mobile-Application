using System.Security.Claims;
using api.DTOs;
using api.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace api.Controllers;

public class AuthController : ApiControllerBase
{
    private readonly IAuthService _authService;
    private readonly IJwtTokenService _tokenService;

    public AuthController(IAuthService authService, IJwtTokenService tokenService)
    {
        _authService = authService;
        _tokenService = tokenService;
    }

    /// <summary>Exchanges credentials for a signed JWT carrying the user's role.</summary>
    [HttpPost("login")]
    [AllowAnonymous]
    public async Task<IActionResult> Login([FromBody] LoginRequest request, CancellationToken cancellationToken)
    {
        var user = await _authService.AuthenticateAsync(request.Username, request.Password, cancellationToken);
        if (user is null)
        {
            // One generic message for every failure (unknown user, wrong password, disabled account),
            // so the response never reveals whether a username exists.
            return Unauthorized(new { error = "Invalid username or password." });
        }

        var issued = _tokenService.CreateToken(user);
        return Ok(new LoginResponse(issued.Token, user.Role.ToString(), issued.ExpiresAt));
    }

    /// <summary>Who the presented token belongs to. Any authenticated user; handy for checking a token.</summary>
    [HttpGet("me")]
    [Authorize]
    public IActionResult Me() => Ok(new CurrentUserResponse(
        User.FindFirstValue("sub") ?? string.Empty,
        User.Identity?.Name ?? string.Empty,
        User.FindFirstValue(JwtTokenService.RoleClaim) ?? string.Empty));
}
