using api.DTOs;
using api.Models;
using api.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace api.Controllers;

/// <summary>Admin oversight endpoints (#49 to #51). Serves /api/admin. ADMIN only.</summary>
[Authorize(Roles = AppRoles.Admin)]
public class AdminController : ApiControllerBase
{
    private readonly IAdminSyncStatusService _syncStatus;

    public AdminController(IAdminSyncStatusService syncStatus) => _syncStatus = syncStatus;

    /// <summary>
    /// How many Form 1 collections and Form 2 decisions are waiting, retrying, forwarded, need attention, are suspected
    /// duplicates or were superseded. Counts only; the records themselves are not returned.
    /// </summary>
    [HttpGet("sync-status")]
    public async Task<ActionResult<ApiResponse<AdminSyncStatusResponse>>> SyncStatus(CancellationToken cancellationToken) =>
        Success(await _syncStatus.GetAsync(cancellationToken));
}
