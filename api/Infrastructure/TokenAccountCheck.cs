using api.Data;
using api.Services;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.EntityFrameworkCore;

namespace api.Infrastructure;

/// <summary>
/// Ties every authenticated request to a live account. A JWT is stateless: without this, a token stays good until it expires
/// (60 minutes) whatever happens to the account, so deactivating a leaver or a lost phone's owner would not take effect until
/// then. After the signature and lifetime have been checked, this loads the user the token names and refuses it (401) when:
///   - the account no longer exists or is deactivated, or
///   - the token's stamp is not the account's current <see cref="Models.AppUser.SecurityStamp"/>, which changes whenever the
///     account is deactivated or reactivated, its role or CBO changes, or its password is reset.
/// A token with no usable user id or stamp (including one issued before this check existed) is refused the same way, so the
/// worst a refused client has to do is sign in again, which the app already does on any 401.
///
/// Cost: one indexed lookup of two columns per authenticated request. Anonymous endpoints (login, health) never reach it.
/// </summary>
public static class TokenAccountCheck
{
    public static async Task ValidateAsync(TokenValidatedContext context)
    {
        var principal = context.Principal;
        var idClaim = principal?.FindFirst("sub")?.Value;
        var stampClaim = principal?.FindFirst(JwtTokenService.StampClaim)?.Value;

        if (!Guid.TryParse(idClaim, out var userId) || !Guid.TryParse(stampClaim, out var stamp))
        {
            context.Fail("The token does not identify an account.");
            return;
        }

        var db = context.HttpContext.RequestServices.GetRequiredService<AppDbContext>();
        var current = await db.Users.AsNoTracking()
            .Where(u => u.Id == userId)
            .Select(u => new { u.IsActive, u.SecurityStamp })
            .SingleOrDefaultAsync(context.HttpContext.RequestAborted);

        if (current is null || !current.IsActive || current.SecurityStamp != stamp)
            context.Fail("The account was deactivated or changed; sign in again.");
    }
}
