# Contributing

Project layout: `client/` (Android app), `api/` (ASP.NET Core backend), `api.Tests/` (backend tests),
`external-api-sim/` (Foodspace simulator). Backend conventions are in [api/README.md](api/README.md).

## Continuous integration

Two GitHub Actions workflows in `.github/workflows/`. Each runs on pull requests to `main`, on pushes to `main`,
and on demand (`gh workflow run <file>`), **only** when files in its paths change:

| Workflow | Runs | Triggers on changes to |
|---|---|---|
| `ci-android.yml` | JDK 17, `./gradlew assembleDebug lint test` in `client/` | `client/**`, the workflow file |
| `cd-backend.yml` | .NET 10: restore, build, `dotnet test api.Tests`, then `docker build` for `api` and `external-api-sim` | `api/**`, `api.Tests/**`, `external-api-sim/**`, `compose.yaml`, `dotnet-tools.json`, the workflow file, and the Room entities / `UserRole.kt` under `client/app/src/main/java/com/example/client/{data,auth}` (because `RoomParityTests` reads them) |

So an Android UI change does not run the backend pipeline, and a backend change does not run the Android one.

Run the same checks locally before opening a PR:

```bash
# backend
dotnet test api.Tests

# android (needs JDK 17; newer JDKs fail the Kotlin 1.9 build)
cd client && ./gradlew assembleDebug lint test
```

### Secrets

**No GitHub Actions secrets are required today.** The backend tests run against an in-memory database with
test-only JWT settings hard-coded in `api.Tests/ApiFactory.cs`, and the Docker steps only build images.
Real values are never committed (`.env` and `local.properties` are gitignored).

When a deploy/registry-push step is added, these are the names to create under
*Settings -> Secrets and variables -> Actions* (none exist yet; add real values, not placeholders):

| Name | Format / purpose |
|---|---|
| `ConnectionStrings__Default` | Npgsql connection string, e.g. `Host=...;Port=5432;Database=...;Username=...;Password=...`. The backend reads it from this env var. |
| `JWT_SIGNING_KEY` | Random secret, at least 32 characters (`openssl rand -base64 48`). The API refuses to start with a shorter one. Maps to `Jwt__SigningKey`. |
| `POSTGRES_PASSWORD` | Database password, when CI or a deploy starts Postgres through `compose.yaml`. |
| Registry credentials | Whatever the chosen registry needs (e.g. `REGISTRY_USERNAME` / `REGISTRY_PASSWORD`). |

`SEED_TEST_PASSWORD` (seeded test users) is for local development only and must not be used outside it.
