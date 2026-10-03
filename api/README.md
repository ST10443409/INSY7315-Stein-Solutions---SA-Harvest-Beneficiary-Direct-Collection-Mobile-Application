# SA Harvest backend (`api`)

ASP.NET Core (.NET 10), controller-based. PostgreSQL through EF Core.

```
Controllers/     HTTP endpoints (all derive from ApiControllerBase)
Infrastructure/  Exception middleware, route-token transformer, envelope wiring
Models/          Entities (mirror the Android Room entities)
Data/            AppDbContext + converters
DTOs/            Request/response shapes, incl. the ApiResponse envelope
Services/        Queue and background worker
Migrations/      EF Core migrations
```

## API conventions (read before adding a controller)

**Routes.** Every controller derives from `ApiControllerBase`, which declares `[Route("api/[controller]")]`.
`OrdersController` is served at `/api/orders`; multi-word names are kebab-cased (`AccessDemoController` ->
`/api/access-demo`). Do not put a controller-level `[Route]` on your controller, only action templates
(`[HttpGet("{id}")]`). `api.Tests` fails if a controller breaks this.

**Response envelope.** New endpoints return `ApiResponse<T>` (`DTOs/ApiResponse.cs`):

```jsonc
// success                                  // failure
{ "success": true,                          { "success": false,
  "data": { ... },                            "data": null,
  "error": null }                             "error": { "code": "NOT_FOUND", "message": "...", "traceId": "..." } }
```

Use `Success(data)` / `Failure(status, code, message)` from the base class. `error.code` is a stable
UPPER_SNAKE_CASE value clients can branch on; `error.message` is user-safe; `error.details` (field -> messages)
appears on `VALIDATION_FAILED`. Errors the framework produces are wrapped automatically: unhandled exceptions
(`500 INTERNAL_ERROR`, logged with the same `traceId`, never a stack trace), model-validation failures
(`400 VALIDATION_FAILED`), and bodyless `401`/`403`/`404` responses.

The pre-existing `POST /api/auth/login` and `GET /api/auth/me` still return their original
bare bodies because the Android client parses them as-is; move them onto the envelope together with the client.

**Health.** `GET /api/health` (anonymous) returns `200` with `data.status = "ok"` when the API and database are
reachable, or `503` with `data.status = "unhealthy"` when the database is not. It never exposes connection
details or exception text.

**OpenAPI.** In Development the document is at `/openapi/v1.json`; it is not served in other environments.

## Database configuration

Secrets are **never committed**. The one approach the team uses is **`dotnet user-secrets`** for local
development, and an environment variable in Docker.

`appsettings.Development.json` only holds a placeholder connection string. Override it once per machine
(the value is stored outside the repo, in your user profile):

```bash
cd api
dotnet user-secrets set "ConnectionStrings:Default" "Host=localhost;Port=5432;Database=saharvest;Username=saharvest;Password=<your password>"
```

The password must match `POSTGRES_PASSWORD` in your `.env` (see below).

### Running Postgres

```bash
cp .env.example .env        # then set POSTGRES_PASSWORD (.env is gitignored)
docker compose up -d db
```

### Migrations

`dotnet-ef` is pinned in `dotnet-tools.json` at the repo root (`dotnet tool restore`). From `api/`, with
`ASPNETCORE_ENVIRONMENT=Development` so user-secrets are loaded:

```bash
dotnet ef migrations add <Name>
dotnet ef database update
```

`docker compose up --build` also applies migrations when the `api` container starts
(`Database__MigrateOnStartup=true` in `compose.yaml`). It is off by default outside Docker.

## Keeping the model in sync with the Android app

`AppDbContext` documents how it maps to the Room entities and where it deliberately differs. The tests in
`api.Tests` read the Kotlin entities and fail if a field name, type or nullability drifts:

```bash
dotnet test api.Tests
```

If you change a Room entity, update the matching model and add a migration in the same PR.

## Authentication (JWT)

`POST /api/auth/login` with `{"username": "...", "password": "..."}` returns
`{"token": "<jwt>", "role": "CBO_COLLECTION|VETTING|ADMIN", "expiresAt": "..."}`; wrong credentials of any kind
return `401 {"error":"Invalid username or password."}`. Send the token as `Authorization: Bearer <token>`.

- **Role claim:** the JWT carries a `role` claim whose value is exactly the Android `UserRole` name
  (`CBO_COLLECTION`, `VETTING`, `ADMIN`). `api.Tests` fails if the two enums drift. Use
  `[Authorize(Roles = AppRoles.Admin)]` etc.; every endpoint says its roles explicitly, and `docs/role-audit-checklist.md` is the audited table (`RoleAuthorizationMatrixTests` fails if an endpoint is added without a row).
- **CBO:** a `CBO_COLLECTION` user belongs to one CBO (`users.cbo_id`, set by whoever creates the account; Vetting and Admin
  users have none). It is returned in the login response (`cboId`) and carried in the token as the `cbo_id` claim.
- **Configuration** (section `Jwt`): `Issuer`, `Audience`, `ExpiryMinutes` (default 60) live in `appsettings.json`.
  **`Jwt:SigningKey` is a secret** and must be at least 32 characters; the app refuses to start without it.
  ```bash
  cd api
  dotnet user-secrets set "Jwt:SigningKey" "$(openssl rand -base64 48)"
  ```
  In Docker it comes from `JWT_SIGNING_KEY` in `.env`.
- **Passwords** are hashed with ASP.NET Core's `PasswordHasher` (PBKDF2, per-user salt).
- **Test users** (development only): with `Seed:Enabled=true` (set in `compose.yaml`) the app creates
  `cbo_test_user` (CBO `cbo-test-001`), `vetting_test_user` and `admin_test_user`, all using the password in `SEED_TEST_PASSWORD`
  (choose your own in `.env`; the value once committed in `.env.example` is public, never reuse it). Their password follows the
  configured one on every start. The API refuses to start with `Seed:Enabled` outside Development. For `dotnet run` set
  `Seed:Enabled` and `Seed:TestUserPassword` via user-secrets.
- **Rate limit:** 10 login attempts per client address per minute (`Security:LoginPermitLimit`, `LoginWindowSeconds`); over it,
  `429 TOO_MANY_REQUESTS` with `Retry-After`.
- **Ports:** in Docker the API is on `http://localhost:5000` (what the Android emulator reaches as `10.0.2.2:5000`).

## Security (#54)

The review, its findings and what each change is for: [`docs/security/security-review.md`](../docs/security/security-review.md).

- **HTTPS is required** outside Development (`Security:RequireHttps`, off only in `appsettings.Development.json`). A plain-HTTP
  call gets `403 HTTPS_REQUIRED`; it is never redirected (the token would already have crossed the network). `/api/health` is
  exempt for platform probes. HTTPS responses carry HSTS.
- **Behind a TLS-terminating proxy** (Azure, #58) the app sees plain HTTP and relies on `X-Forwarded-Proto`, which it believes only
  from `Security:KnownNetworks` (CIDR list; loopback always). Set it to the ingress range, e.g. `Security__KnownNetworks__0=10.0.0.0/8`,
  or `0.0.0.0/0` and `::/0` when the container has no public port. If it is wrong, every call fails with `HTTPS_REQUIRED`: loud,
  never silently insecure. The same setting gives the login rate limit the client's real address rather than the proxy's.
- **The API refuses to start** (listing every problem) outside Development with `Seed:Enabled`, and whenever HTTPS is required
  with a non-`https://` Foodspace address or an unencrypted database connection (add `SSL Mode=VerifyFull`, or `Require`, unless
  the database is on the same host). See `Infrastructure/SecurityStartupChecks.cs`.
- **Compressed requests:** the app gzips sync batches (`Content-Encoding: gzip`), about 85% smaller; `UseRequestDecompression`
  unpacks them. Size limits apply after decompression: 16 KB for login, 4 MB for the sync endpoints (`413 PAYLOAD_TOO_LARGE`).
- **Local Docker** (`compose.yaml`) runs as Development on purpose: plain HTTP for the emulator, and the seeded test users.

## Forwarding to Foodspace

Records that reach this backend are forwarded to Foodspace by `FoodspaceApiClient` (typed `HttpClient`), driven by
`CboCollectionForwarder` and a background loop (`FoodspaceForwardingWorker`). Until we have access to Foodspace,
`external-api-sim` stands in for it.

- **Status:** `ForwardingStatus` (server-only) is `PENDING` -> `FORWARDED`, or `SYNCED_LOCAL_PENDING_FOODSPACE` when
  Foodspace did not accept the record. That state is retried with exponential backoff (`Foodspace:BaseDelaySeconds`,
  `MaxAttempts`); when retries run out it stays there for an Admin to retry or dismiss (see "Admin oversight endpoints"). `SyncStatus` is untouched, so a
  collector's record is never marked failed because of Foodspace.
- **Ingestion:** `POST /api/cbo-collection/sync` stores the record; the forwarding loop then picks it up within `PollIntervalSeconds`.
- **Several workers are safe.** Before sending a record, a worker claims it in the database (`forward_claim_id` is a concurrency
  token, `forward_claimed_until` the lease; `Foodspace:ClaimLeaseSeconds`, default 120, must exceed `TimeoutSeconds`). Of two
  workers that both read the same row (a second instance, the old and new container during a deployment, the loop and an
  Admin's retry) the database lets one through and the other skips the record. A worker that dies mid-send delays its record by
  at most the lease. An Admin dismissing a record at the instant a worker claims it gets `409`, "being sent right now".
  See `ForwardingClaim` for why the claim id must never return to an earlier value.
- **Config** (section `Foodspace`): `BaseUrl` (falls back to `ExternalApi:BaseUrl`), `ApiKey` (**secret**, sent as `X-Api-Key`;
  set with user-secrets or `Foodspace__ApiKey`), `ForwardingEnabled`, `PollIntervalSeconds`, `MaxAttempts`, `ClaimLeaseSeconds`, `LogPayloads`
  (off by default: payloads contain donor names). The auth mechanism is an assumption until Foodspace confirms it.
- **Mapping:** `FoodspaceCboCollectionMapper` is the single place our fields map to Foodspace's; its comment is the mapping table.
- **Simulate an outage:** `POST http://localhost:5284/api/external/simulate/outage` (optionally `?status=500`), then
  `POST .../simulate/recover`. `GET .../api/external/received` shows what Foodspace has received.

## CBO collection sync endpoint

`POST /api/cbo-collection/sync` (roles `CBO_COLLECTION` or `ADMIN`) takes `{ "records": [ ... up to 100 ... ] }` and answers
`200 { success, data: { results: [ { clientId, success, alreadyReceived, error, errorCode, retryable } ] } }`, one result per
record in the same order. Each record is validated and stored on its own, so one bad record never affects the others. Only a
request unusable as a whole (no records, more than 100) is a `400`.

- **The CBO comes from the account, not the request:** when the token has a `cbo_id` claim (every collector's does), the
  server overwrites each record's `cboId` with it before validating and before computing the duplicate key. A device that
  does not know its CBO yet, or sends the wrong one, is stored (and duplicate-matched) under the right CBO. Users without
  the claim (Admins) are stored with the `cboId` they send.
- **Device-only fields** (`retryCount`, `syncErrorCode`, `syncStatus`) are ignored if sent: the server keeps its own.
- **Permanent vs transient:** `VALIDATION_FAILED` has `retryable: false` (don't resend the same data); `SERVER_ERROR` has `retryable: true`.
- **Idempotency:** the client UUID is the primary key. An id that is already stored is reported as a success with
  `alreadyReceived: true` and nothing is written ("already exists, treat as success"; the first write wins and a retry with
  different data does not overwrite it). It can't yet tell a legitimate retry from two collectors reusing an id: that is #38.

### Retry vs duplicate (#38)

Two cases look alike and are handled differently:

| | Same client id again (**retry**, #36) | Different client id, same real-world collection (**duplicate**, #38) |
|---|---|---|
| Meaning | The first response was lost | Two collectors, or a device that lost its data |
| Result | `success: true, alreadyReceived: true` | `success: false, errorCode: "DUPLICATE_DETECTED", duplicateOfId, retryable: false` |
| Stored? | Nothing written; first write wins | Kept (with `duplicate_of_id` set) for Admin review; never merged into the original |
| Foodspace | n/a | Not forwarded |

- **Matching rule** (`CboCollectionDuplicateKey`, one small class, easy to change): same CBO + donor name + collection date
  (South African day of `createdAt`) + delivery note number; case and whitespace ignored. A different delivery note is a
  separate pick-up, not a duplicate. This is a product decision, so confirm it with the people who run the collections.
- **Database level:** `duplicate_key` (a SHA-256 of the rule's inputs, so no donor name in the index) has a partial unique
  index `WHERE duplicate_of_id IS NULL`: at most one original per real-world collection, even for simultaneous requests.
- Re-sending a stored duplicate returns `DUPLICATE_DETECTED` again, so the app never mistakes it for a synced record.

## Vetting records endpoint (#43)

`GET /api/vetting/records` (roles `VETTING` or `ADMIN`; a `CBO_COLLECTION` token gets `403`) returns the beneficiary records a
vetting officer reviews on Form 2, one page at a time:

```
GET /api/vetting/records?page=1&pageSize=50&province=Gauteng
200 { success, data: { items: [ ... ], page, pageSize, totalCount, hasMore, fetchedAt, stale } }
```

- **Fields:** each item has exactly the fields of the Android `FoodspaceBeneficiaryRecord` entity and nothing else. Anything else
  Foodspace sends is dropped when it is read, and a test compares the response field-for-field with the Kotlin entity.
- **Paging:** `page` starts at 1; `pageSize` defaults to 50, at most 100 (otherwise `400 VALIDATION_FAILED` naming the field).
  Records are ordered by id, so paging never skips or repeats a record. Keep asking for `page + 1` while `hasMore` is true.
  A page past the end is empty, not an error. `province` filters case-insensitively and `totalCount` reflects it.
- **Why a cache:** Foodspace's beneficiary endpoint can only filter by province; it cannot page or filter by date. Paging straight
  through to it would download the whole set for every page of every officer. So the full list is fetched from Foodspace at
  most once per `Foodspace:BeneficiaryCacheMinutes` (default 15), stored in `foodspace_beneficiary_records` (fetch-and-replace,
  in one save), and pages are cut from there. A burst of officers syncing together asks Foodspace once.
- **Foodspace down:** if the cache has expired and Foodspace cannot be reached, the last copy is still served with
  `stale: true`. `fetchedAt` says how old it is, so the app can show "updated 40 min ago". Only when nothing has ever been
  cached is the answer `503 FOODSPACE_UNAVAILABLE`. This is not an error the app should treat as "no records": an officer
  who already has records on the device keeps them.
- **Bad data from Foodspace:** one unreadable beneficiary is skipped (logged as a count, never its contents) without hiding the
  rest. If everything sent is unreadable the cached list is kept instead of being replaced with nothing.
- **Not built (needs Foodspace to confirm):** a `since` / delta mode. It needs Foodspace to expose a change timestamp and to
  report removals; see `docs/OPEN-DECISIONS.md`.

**Measured payload** (`APageOfFifty_IsSmall_AndMuchSmallerOverTheWireWhenCompressed`, which prints it): a page of 50 records is
about **76 KB** of JSON (1.5 KB per record) and about **3.2 KB** gzipped. Treat the gzip figure as a best case: the test
records are near-identical, so real data will compress less. The API compresses responses (gzip/brotli) when the client asks;
OkHttp on Android does this automatically. It is not applied to HTTPS requests that reach Kestrel directly (the framework's
BREACH precaution), only behind a TLS-terminating host or over plain HTTP. In the other direction, the app gzips its sync
batches (see "Security" above). If Foodspace's `kitchenImages`, `facilityPhotos` or
`certificates` hold image data rather than links, pages will be far larger: see `docs/OPEN-DECISIONS.md`.

## Vetting decisions endpoint (#47)

`POST /api/vetting/sync` (roles `VETTING` or `ADMIN`; a `CBO_COLLECTION` token gets `403`) takes `{ "records": [ ... up to 100 ... ] }`
and answers `200 { success, data: { results: [ { clientId, success, alreadyReceived, error, errorCode, retryable } ] } }`, one result
per decision in the same order. It is deliberately the same shape and rules as the CBO collection sync above (same result
fields, same `VALIDATION_FAILED` = permanent / `SERVER_ERROR` = retryable codes, same `400` only for an unusable batch), so the
app reads both with the same code.

- **Fields:** the Room fields of `VettingDecision`: `id` (UUID), `foodspaceRecordId`, `outcome` (`APPROVE`, `REJECT` or `FLAG`,
  exactly), `notes` (optional, up to 4000 characters), `decisionTimestamp`, `createdAt`, `updatedAt` (epoch ms). Device-only
  fields (`syncStatus`) are ignored.
- **The officer comes from the token.** Whatever `officerId` the device sends is replaced by the signed-in user's name, so a
  decision cannot be recorded as somebody else.
- **Idempotency:** the client id is the primary key. A resent id is a success with `alreadyReceived: true` and nothing is
  written (first write wins). The exception: an id already stored for a *different* officer is refused (`VALIDATION_FAILED`),
  because a UUID does not collide by accident and answering "received" would let a device discard a decision we never stored.
- **The beneficiary is not checked against our cache.** The cache is replaced on every refresh and may be empty or minutes
  behind, so refusing on it would permanently reject honest decisions. Foodspace is the authority: if it does not know the
  beneficiary the decision is kept and stays "saved here, not yet in Foodspace" (below) for an Admin.

### Forwarding decisions to Foodspace

Stored decisions are forwarded by `VettingDecisionForwarder` on the same background loop as collections, to
`POST /api/external/vetting-decisions`, with the same status and retry rules as collections (`ForwardingStatus`: `PENDING` →
`FORWARDED`, or `SYNCED_LOCAL_PENDING_FOODSPACE` when Foodspace did not accept it, retried with exponential backoff, then left
for an Admin). `SyncStatus` is never touched, so an officer's decision is never shown as failed because of Foodspace.

**Only the latest decision per beneficiary is sent.** An officer who flags a record and later approves it keeps both decisions
here (the audit trail), but Foodspace only ever hears the newest. An older decision is marked `SUPERSEDED` and never sent,
even by a manual forward. "Newer" is by decision time, then by when the device created it, whoever made it. Decisions are
forwarded oldest first, so Foodspace's last word is always the newest. Foodspace was assumed to want this; confirm it (see
`docs/OPEN-DECISIONS.md`).

## Admin oversight endpoints (#49, #50, #51)

All under `/api/admin`, `ADMIN` only (`AdminController`). Every record that reaches this backend is in exactly one `SyncState`,
derived in one place (`SyncStates.Of`) from `ForwardingStatus`, whether a retry is scheduled, and whether it is a suspected duplicate:

| State | Meaning |
|---|---|
| `WAITING` | Received, not yet sent to Foodspace |
| `RETRYING` | Foodspace has not accepted it; an automatic retry is scheduled |
| `NEEDS_ATTENTION` | Foodspace has not accepted it and no retry is scheduled (rejected, or retries used up) |
| `FORWARDED` | Foodspace accepted it |
| `DUPLICATE_HELD` | Form 1: looks like a collection already recorded; held, never sent until an Admin releases it |
| `SUPERSEDED` | Form 2: a newer decision replaced it; history, never sent |
| `DISMISSED` | An Admin chose not to send it; kept as the audit trail |

| Endpoint | What it does |
|---|---|
| `GET /api/admin/sync-status` | Counts per state for both forms (#49). Counts only, no record data. |
| `GET /api/admin/sync-status/attention?form=&page=&pageSize=` | The records that need an Admin (`NEEDS_ATTENTION` and `DUPLICATE_HELD`), oldest first, with the error and attempt count. `form` is `CBO_COLLECTION` or `VETTING_DECISION`. |
| `GET /api/admin/sync-status/{id}?form=` | One record: the error detail, attempts, the record a duplicate matched, `canRetry` / `canDismiss`, and what has already been done to it. |
| `POST /api/admin/sync-status/{id}/retry?form=` | Sends it to Foodspace now and returns where it ended up (`record.state`). |
| `POST /api/admin/sync-status/{id}/dismiss?form=` | Body `{ "reason": "..." }` (required, 500 characters at most). Marks it as never to be sent. |

- **Retry** starts the record's attempt count again, so if Foodspace is still failing it gets a fresh automatic-retry budget instead of staying at
  "retries used up". It is allowed unless Foodspace already has the record or a newer decision replaced it (409). On a `DUPLICATE_HELD`
  record it means "this is a real collection, send it": the record is released and then retried like any other. A dismissed record can be
  brought back the same way. A retry that Foodspace rejects again is still a `200`: the answer is in `record.state` and `record.error`.
- **Dismiss** is only for a record that needs attention or a held duplicate (409 otherwise): a record that is still retrying will sort itself out.
- **`form`** is only needed if a collection and a decision somehow share an id (ids are chosen by devices): without it that id answers 409.
- **Accountability:** every retry and dismissal is a row in `admin_actions` (who, which record, when, previous and resulting status,
  previous attempt count, the reason) and a log line. Rows are only added. The record's `history` shows them, newest first.
- **Not here: editing a record before resubmitting.** See `docs/decisions/0002-failed-sync-resolution.md` for why, and what would be needed.

### User activity (#51)

`GET /api/admin/user-activity` lists who submitted or vetted what, newest first: Form 1 collections and Form 2 decisions in one list, each with the
acting user, their role, the time, and a record reference (`form` + `id`, plus a one-line `label`). Read-only: nothing here changes a record.

| Query | Meaning |
|---|---|
| `user` | Username, any case |
| `role` | `CBO_COLLECTION`, `VETTING` or `ADMIN` (an Admin's own work counts under `ADMIN`) |
| `from`, `to` | `yyyy-MM-dd`, South African days, `to` inclusive. Either can be used alone for an open range. Years 2000 to 2100. |
| `page`, `pageSize` | Default 50, at most 100. Ask for `page + 1` while `hasMore` is true. |

- **Default window:** with neither `from` nor `to`, only the last 7 South African days (today included) are returned, so the whole history is never
  the default. The answer's `from` / `to` say what was applied. Using any filter other than a date still gets the default window.
- **"When"** is the time on the device when the work was done (`createdAt` / `decisionTimestamp`), so work done offline counts on the day it was done,
  not the day it synced; `receivedAt` says when the server heard about it. A device with a wrong clock therefore shows work on the wrong day.
- **Role** is the one on the account today (records only store the username), so it is null for a user who no longer exists, and a role filter leaves
  out Form 1 records from before submitters were recorded (their `user` is null).
- The date and user filters run on unindexed columns (`created_at`, `decision_timestamp`, `submitted_by`; only `officer_id` has an index). That is fine at this size; add
  indexes with the rest of #56 if the lists grow.

## User accounts (Admin endpoints and the first-admin bootstrap)

Login accounts are created and managed by an Admin through `/api/admin/users`; the operator guide, with `curl` examples, the password
and username rules and the guard rails, is [docs/user-accounts.md](../docs/user-accounts.md).

| Endpoint | What it does |
|---|---|
| `GET /api/admin/users` | list, filter by `role`, `active`, `search`; paged |
| `GET /api/admin/users/{id}` | one account with its history (newest first) |
| `POST /api/admin/users` | create (201); a collector needs a `cboId`, the other roles must not have one |
| `PATCH /api/admin/users/{id}` | change `role`, `cboId` and/or `isActive`; absent fields are left alone |
| `POST /api/admin/users/{id}/reset-password` | set a new password |

- **Sessions end at once.** A token carries a `stamp` claim, `TokenAccountCheck` compares it with the account's current
  `AppUser.SecurityStamp` on every authenticated request, and the stamp changes on deactivate/reactivate, role or CBO change and password
  reset. A token for an account that is gone, deactivated or re-stamped is a 401. Cost: one indexed two-column lookup per request.
- **Audit.** Every change writes a `user_audit` row in the same save (actor, action, target, detail; never a secret).
- **First admin.** `Bootstrap:AdminUsername` and `Bootstrap:AdminPassword` (a secret) create the first Admin at start-up, once, and only
  if no active Admin exists; they never change an existing account, and a half-set or unacceptable value stops the start-up
  (`AdminBootstrapper`). Remove the settings after the first sign-in. The test-user seeder remains Development-only.
- Rules live in one place, `AccountRules`, shared by the bootstrap and the endpoints.
