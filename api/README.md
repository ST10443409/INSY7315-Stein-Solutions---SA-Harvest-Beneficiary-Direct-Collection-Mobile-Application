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

The pre-existing `POST /api/auth/login`, `GET /api/auth/me` and `POST /api/sync` still return their original
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
  `[Authorize(Roles = AppRoles.Admin)]` etc.; `Controllers/AccessDemoController.cs` shows the pattern (delete it once real endpoints exist).
- **Configuration** (section `Jwt`): `Issuer`, `Audience`, `ExpiryMinutes` (default 60) live in `appsettings.json`.
  **`Jwt:SigningKey` is a secret** and must be at least 32 characters; the app refuses to start without it.
  ```bash
  cd api
  dotnet user-secrets set "Jwt:SigningKey" "$(openssl rand -base64 48)"
  ```
  In Docker it comes from `JWT_SIGNING_KEY` in `.env`.
- **Passwords** are hashed with ASP.NET Core's `PasswordHasher` (PBKDF2, per-user salt).
- **Test users** (development only): with `Seed:Enabled=true` (set in `compose.yaml`) the app creates
  `cbo_test_user`, `vetting_test_user` and `admin_test_user`, all using the password in `SEED_TEST_PASSWORD`
  (`.env.example` has a dev-only value). For `dotnet run` set `Seed:Enabled` and `Seed:TestUserPassword` via user-secrets.
- **Ports:** in Docker the API is on `http://localhost:5000` (what the Android emulator reaches as `10.0.2.2:5000`).

## Forwarding to Foodspace

Records that reach this backend are forwarded to Foodspace by `FoodspaceApiClient` (typed `HttpClient`), driven by
`CboCollectionForwarder` and a background loop (`FoodspaceForwardingWorker`). Until we have access to Foodspace,
`external-api-sim` stands in for it.

- **Status:** `ForwardingStatus` (server-only) is `PENDING` -> `FORWARDED`, or `SYNCED_LOCAL_PENDING_FOODSPACE` when
  Foodspace did not accept the record. That state is retried with exponential backoff (`Foodspace:BaseDelaySeconds`,
  `MaxAttempts`); when retries run out it stays there for an Admin to retry (#49/#50). `SyncStatus` is untouched, so a
  collector's record is never marked failed because of Foodspace.
- **Ingestion:** `POST /api/cbo-collection/sync` stores the record; the forwarding loop then picks it up within `PollIntervalSeconds`.
- **Config** (section `Foodspace`): `BaseUrl` (falls back to `ExternalApi:BaseUrl`), `ApiKey` (**secret**, sent as `X-Api-Key`;
  set with user-secrets or `Foodspace__ApiKey`), `ForwardingEnabled`, `PollIntervalSeconds`, `MaxAttempts`, `LogPayloads`
  (off by default: payloads contain donor names). The auth mechanism is an assumption until Foodspace confirms it.
- **Mapping:** `FoodspaceCboCollectionMapper` is the single place our fields map to Foodspace's; its comment is the mapping table.
- **Simulate an outage:** `POST http://localhost:5284/api/external/simulate/outage` (optionally `?status=500`), then
  `POST .../simulate/recover`. `GET .../api/external/received` shows what Foodspace has received.

## CBO collection sync endpoint

`POST /api/cbo-collection/sync` (roles `CBO_COLLECTION` or `ADMIN`) takes `{ "records": [ ... up to 100 ... ] }` and answers
`200 { success, data: { results: [ { clientId, success, alreadyReceived, error, errorCode, retryable } ] } }`, one result per
record in the same order. Each record is validated and stored on its own, so one bad record never affects the others. Only a
request unusable as a whole (no records, more than 100) is a `400`.

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
