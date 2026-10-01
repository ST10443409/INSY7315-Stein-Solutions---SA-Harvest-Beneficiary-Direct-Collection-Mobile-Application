# Endpoint Role Audit

| Endpoint Path | Intended Role(s) | Actual Attribute | Verified (Tests) |
| --- | --- | --- | --- |
| `/api/cbo-collection/sync` | `CboCollection`, `Admin` | `[Authorize(Roles = $"{AppRoles.CboCollection},{AppRoles.Admin}")]` | Yes (CboCollectionSyncEndpointTests) |
| `/api/vetting/sync` | `Vetting`, `Admin` | `[Authorize(Roles = $"{AppRoles.Vetting},{AppRoles.Admin}")]` | Yes (VettingDecisionSyncEndpointTests) |
| `/api/vetting/records` | `Vetting`, `Admin` | `[Authorize(Roles = $"{AppRoles.Vetting},{AppRoles.Admin}")]` | Yes (VettingRecordsEndpointTests) |
| `/api/admin/sync-status` | `Admin` | `[Authorize(Roles = AppRoles.Admin)]` | Yes (AdminSyncStatusEndpointTests) |
| `/api/admin/sync-status/attention` | `Admin` | `[Authorize(Roles = AppRoles.Admin)]` | Yes (AdminSyncResolutionEndpointTests) |
| `/api/admin/sync-status/{id}` | `Admin` | `[Authorize(Roles = AppRoles.Admin)]` | Yes (AdminSyncResolutionEndpointTests) |
| `/api/admin/sync-status/{id}/retry` | `Admin` | `[Authorize(Roles = AppRoles.Admin)]` | Yes (AdminSyncResolutionEndpointTests) |
| `/api/admin/sync-status/{id}/dismiss` | `Admin` | `[Authorize(Roles = AppRoles.Admin)]` | Yes (AdminSyncResolutionEndpointTests) |
| `/api/admin/user-activity` | `Admin` | `[Authorize(Roles = AppRoles.Admin)]` | Yes (AdminUserActivityEndpointTests) |
| `/api/sync` | `CboCollection`, `Vetting`, `Admin` | `[Authorize(Roles = $"{AppRoles.CboCollection},{AppRoles.Vetting},{AppRoles.Admin}")]` | Yes (SyncControllerEndpointTests) |
| `/api/health` | (Open) | `[AllowAnonymous]` | Yes |
| `/api/auth/login` | (Open) | `[AllowAnonymous]` | Yes |
| `/api/auth/me` | Any valid user | `[Authorize]` | Yes |

*Note: The `/api/sync` endpoint was missing role checks but has been corrected in this PR.*
