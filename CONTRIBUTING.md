# Contributing

Project layout: `client/` (Android app), `api/` (ASP.NET Core backend), `api.Tests/` (backend tests),
`external-api-sim/` (Foodspace simulator). Backend conventions are in [api/README.md](api/README.md).

## Continuous integration

Branches: work on `feature/*` (or `feat/*`, `fix/*`) branches cut from `development`, and open a pull request back into
`development`. When `development` has been tested, a pull request from `development` into `main` releases it. Nobody pushes
to `development` or `main` directly (see [docs/branch-protection.md](docs/branch-protection.md)).

Two GitHub Actions workflows in `.github/workflows/`. Each runs on pull requests to `development` or `main`, on pushes to
either, and on demand (`gh workflow run <file>`). Each starts with a quick `changes` job that checks which files the change
touched ([.github/scripts/changed-paths.sh](.github/scripts/changed-paths.sh)); the real job is skipped when none of its
paths changed, and GitHub counts a skipped job as passed. (This is a job rather than a `paths:` filter on the trigger
because a workflow a filter stops from starting never reports its check, and a required check that never reports blocks
the pull request forever.)

| Workflow | Runs | Real job runs on changes to |
|---|---|---|
| `ci-android.yml` (job `build`) | JDK 17, `./gradlew assembleDebug lint test` in `client/`; **plus the emulator tests only for pull requests into `main`, pushes to `main` and manual runs** (they take most of an hour, so `development` pull requests skip them) | `client/**`, the workflow file |
| `cd-backend.yml` (job `build-test-docker`) | .NET 10: restore, build, `dotnet test api.Tests` (with a throwaway PostgreSQL service container, so every migration is applied to an empty database and the forwarding-claim tests run on real SQL; see [docs/testing.md](docs/testing.md)), then `docker build` for `api` and `external-api-sim` | `api/**`, `api.Tests/**`, `external-api-sim/**`, `compose.yaml`, `dotnet-tools.json`, the workflow file, and the Room entities / `UserRole.kt` under `client/app/src/main/java/com/example/client/{data,auth}` (because `RoomParityTests` reads them) |

So an Android UI change does not run the backend pipeline, and a backend change does not run the Android one.

A third workflow, `secret-scan.yml` (job `gitleaks`), runs gitleaks on **every** pull request and push to `development` and
`main` (their new commits only)
and weekly over the whole history (#54). If it flags something real, rotate the secret first (it is already in history),
then remove it. If it is a false positive, add a commented exception to `.gitleaks.toml` and note it in
[`docs/security/security-review.md`](docs/security/security-review.md).

**Release builds of the app** need an HTTPS API address: `./gradlew assembleRelease -PapiBaseUrl=https://<host>/`. Without
one the build stops, because release builds refuse plain HTTP.

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
