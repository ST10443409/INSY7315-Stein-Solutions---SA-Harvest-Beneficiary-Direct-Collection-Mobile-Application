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
