# SA Harvest backend (`api`)

ASP.NET Core (.NET 10), controller-based. PostgreSQL through EF Core.

```
Controllers/   HTTP endpoints
Models/        Entities (mirror the Android Room entities)
Data/          AppDbContext + converters
DTOs/          Request/response shapes
Services/      Queue and background worker
Migrations/    EF Core migrations
```

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
