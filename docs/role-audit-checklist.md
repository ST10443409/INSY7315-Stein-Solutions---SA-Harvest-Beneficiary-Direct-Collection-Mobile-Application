# Endpoint role audit (#52)

Backend enforcement of "each user only reaches the forms and functionality of their role". The Android app's role routing
(#26) and the `role` claim (#30) are not enforcement on their own: a modified client or a raw `curl` skips both. This is
what stops them, and the audit of it.

The table is a **test as well as a document**. `api.Tests/RoleAuthorizationMatrixTests.cs` reads every endpoint from the
running API and fails when:

- an endpoint exists that has no row below (add the row, in the test and here, and decide its roles on purpose);
- the roles on an endpoint's `[Authorize]` differ from its row, or an endpoint that should be open does not say
  `[AllowAnonymous]`;
- an endpoint, called with no token, does not answer `401`; called with a role not in its row, does not answer `403`; or called
  with a role in its row, answers `401`/`403`;
- a validly signed token whose role is missing, unknown or the wrong case (`admin`) gets into any role-restricted endpoint;
- this document is missing a row.

Roles are the wire names the API issues: `CBO_COLLECTION`, `VETTING`, `ADMIN`.

## Audit table

| Method | Endpoint | Intended role(s) | Attribute | Verified by |
| --- | --- | --- | --- | --- |
| `POST` | `/api/cbo-collection/sync` | `CBO_COLLECTION`, `ADMIN` | `[Authorize(Roles = "CBO_COLLECTION,ADMIN")]` on the controller | matrix + `CboCollectionSyncEndpointTests` |
| `POST` | `/api/vetting/sync` | `VETTING`, `ADMIN` | `[Authorize(Roles = "VETTING,ADMIN")]` on the controller | matrix + `VettingDecisionSyncEndpointTests` |
| `GET` | `/api/vetting/records` | `VETTING`, `ADMIN` | `[Authorize(Roles = "VETTING,ADMIN")]` on the controller | matrix + `VettingRecordsEndpointTests` |
| `GET` | `/api/admin/user-activity` | `ADMIN` | `[Authorize(Roles = "ADMIN")]` on the controller | matrix + `AdminUserActivityEndpointTests` |
| `GET` | `/api/admin/sync-status` | `ADMIN` | `[Authorize(Roles = "ADMIN")]` on the controller | matrix + `AdminSyncStatusEndpointTests` |
| `GET` | `/api/admin/sync-status/attention` | `ADMIN` | `[Authorize(Roles = "ADMIN")]` on the controller | matrix + `AdminSyncResolutionEndpointTests` |
| `GET` | `/api/admin/sync-status/{id}` | `ADMIN` | `[Authorize(Roles = "ADMIN")]` on the controller | matrix + `AdminSyncResolutionEndpointTests` |
| `POST` | `/api/admin/sync-status/{id}/retry` | `ADMIN` | `[Authorize(Roles = "ADMIN")]` on the controller | matrix + `AdminSyncResolutionEndpointTests` |
| `POST` | `/api/admin/sync-status/{id}/dismiss` | `ADMIN` | `[Authorize(Roles = "ADMIN")]` on the controller | matrix + `AdminSyncResolutionEndpointTests` |
| `GET` | `/api/admin/users` | `ADMIN` | `[Authorize(Roles = "ADMIN")]` on the controller | matrix + `UserManagementEndpointTests` |
| `GET` | `/api/admin/users/{id}` | `ADMIN` | `[Authorize(Roles = "ADMIN")]` on the controller | matrix + `UserManagementEndpointTests` |
| `POST` | `/api/admin/users` | `ADMIN` | `[Authorize(Roles = "ADMIN")]` on the controller | matrix + `UserManagementEndpointTests` |
| `PATCH` | `/api/admin/users/{id}` | `ADMIN` | `[Authorize(Roles = "ADMIN")]` on the controller | matrix + `UserManagementEndpointTests` |
| `POST` | `/api/admin/users/{id}/reset-password` | `ADMIN` | `[Authorize(Roles = "ADMIN")]` on the controller | matrix + `UserManagementEndpointTests` |
| `GET` | `/api/health` | open (platform probes) | `[AllowAnonymous]` | matrix + `HealthAndEnvelopeTests` |
| `POST` | `/api/auth/login` | open (it issues the token) | `[AllowAnonymous]` | matrix + `AuthEndpointTests` |
| `GET` | `/api/auth/me` | any signed-in user | `[Authorize]` | matrix + `AuthEndpointTests` |

## Deliberate choices (not oversights)

**Admin may call the two form endpoints.** The Admin role gets Form 1 and Form 2 inside the Android app
(`docs/decisions/0001-admin-lives-in-the-android-app.md`: the dashboard links to both workflows), so the backend has to accept
what those screens send, for support and for testing a flow end to end. An Admin's record is stamped with the Admin as its
submitter (the server reads it from the token, never from the request), so the activity oversight (#51) shows who really did it.
The reverse is not allowed: a collector or an officer never reaches an Admin endpoint, and collectors and officers never reach
each other's.

**Collectors and officers are kept apart in both directions.** `CBO_COLLECTION` is refused by every Vetting endpoint and
`VETTING` by every Collection endpoint (and by all of Admin), asserted for every pair by the matrix.

**`/api/sync` has been removed.** It was the original generic queue endpoint from Sprint 1: it queued an arbitrary payload in
memory and a background worker posted it to the external API's `/api/external/sync` (the simulator). Nothing in the app calls
it any more, and in a deployed environment it would have accepted data and silently dropped it (the queue lived in memory and
was lost on every restart), so `SyncController`, `QueueService`, `QueueBackgroundWorker`, `SyncPayload` and the `"ExternalApi"`
HttpClient are gone, and `AuthEndpointTests.TheRetiredGenericSyncEndpoint_IsGone` keeps it that way. Still to do on the
Android side, with the next change to the client: delete the unused `SyncApiService.syncData` declaration (five test fakes
implement it) and the Room `sync_payloads` table, which needs a Room schema version and migration.

**`/api/auth/me` is any signed-in user.** It only echoes the caller's own identity and role.

**Account management (`/api/admin/users`) is ADMIN only, and an Admin's rights are re-checked on every request.** Anyone who can
create an Admin can take over the system, so these five endpoints sit behind the same `[Authorize(Roles = "ADMIN")]` as the rest of
the controller, and the matrix proves every other role gets 403 on each. Two things make that hold up in practice, not only on paper:
a token is checked against the live account on every request (`TokenAccountCheck`: an unknown, deactivated or re-stamped account is a
401, whatever the token's signature and expiry say), so removing someone's Admin role or deactivating them ends their access at once
rather than when their token expires; and an Admin cannot deactivate or re-role themselves, and the last active Admin cannot be
removed. Details and the full rules: `docs/user-accounts.md`.

**Wrong-role and wrong-token results.** A role-restricted endpoint answers `401` with no (or an invalid/expired) token and
`403` with a valid token of a role that is not allowed. The app treats a `403` as "not this record's fault, try later" and
never marks a record failed because of it (`BatchSyncRunner`).

## What changed in this audit

- Every endpoint of Sprints 2 and 3 (#36, #37, #38, #43, #47, #49, #50, #51) already carried an explicit role; none relied on a
  bare `[Authorize]` where a restriction was intended.
- `/api/sync` had no role check (fixed earlier in this audit; the endpoint was later removed altogether, see below).
- The placeholder `AccessDemoController` (`/api/access-demo/*`), kept "until real endpoints use the pattern", was still part of
  the deployed API. It moved to the test project (`api.Tests/AccessDemoController.cs`) so the authentication tests still use it
  but the production surface is only real endpoints.
- New: the matrix test above, so the audit cannot silently go stale.

## Checking a running server by hand

```bash
export CBO_TOKEN=...  VETTING_TOKEN=...  ADMIN_TOKEN=...   # from POST /api/auth/login
API=http://localhost:5000 bash docs/verify-roles.sh        # prints every endpoint x role with the expected and actual status
```

The script exits non-zero if any answer differs from the table, so it can gate a deployment (#58, #60).
