# SA Harvest: Beneficiary Direct Collection (INSY7315, Stein Solutions)

An Android app and a backend API for SA Harvest. Collectors at community-based organisations (CBOs) record food
collections (Form 1, with photos and signatures); vetting officers record beneficiary vetting decisions (Form 2); an Admin
oversees sync, failures and user accounts. Records are saved on the phone first and sync when there is signal.

## Live deployment

| | |
|---|---|
| API (Azure App Service, container) | https://sah-collection-vetting-api.azurewebsites.net |
| Health check | https://sah-collection-vetting-api.azurewebsites.net/api/health (reports the API and its PostgreSQL database) |
| Database | Azure Database for PostgreSQL (flexible server) |
| Photos and signatures | Azure Blob Storage (private container, written through the API) |
| Secrets | Azure Key Vault (read by the API through its managed identity; none are in the repository) |

The Android app talks to the live API by default (`client/app/build.gradle.kts`). The Foodspace integration is switched off
on the live deployment because the Foodspace API is not available to us, so the vetting list shows Foodspace as unavailable
there; the local setup below includes a Foodspace simulator that shows the whole flow.

## Repository layout

| Folder | What it is |
|---|---|
| `client/` | Android app (Kotlin, Jetpack Compose, Hilt, Room) |
| `api/` | Backend (ASP.NET Core, .NET 10, EF Core, PostgreSQL). Conventions in [api/README.md](api/README.md) |
| `api.Tests/` | Backend tests (they run against a real PostgreSQL) |
| `external-api-sim/` | Foodspace simulator for local development |
| `infra/` | Azure infrastructure as code (Bicep) and setup guide: [infra/README.md](infra/README.md) |
| `.github/workflows/` | CI and CD pipelines |
| `docs/` | Testing, security review, user accounts, release and performance notes |

## Branching, CI and CD (Gitflow)

Work happens on `feature/*`, `feat/*` and `fix/*` branches cut from `development`; each goes back by pull request. A pull
request from `development` into `main` is a release. Nobody pushes to `development` or `main` directly
([docs/branch-protection.md](docs/branch-protection.md)). Details: [CONTRIBUTING.md](CONTRIBUTING.md).

- **CI** on every pull request: backend build and tests (with PostgreSQL migrations), Android build, lint and tests,
  workflow and Bicep validation, and a secret scan (gitleaks).
- **CD** on every push to `main` (`deploy.yml`): the CI gates run again, the API image is built and pushed to the GitHub
  Container Registry, the Azure Web App pulls it, a smoke test checks `/api/health`, and the previous image is restored if
  the smoke test fails. The same run builds the signed release APK.

## Run it locally

You need Docker, and Android Studio (or the Android SDK) for the app.

```bash
cp .env.example .env        # then fill in every empty value; .env is gitignored
docker compose up -d        # API on http://localhost:5000, with PostgreSQL, the Foodspace simulator and Azurite
```

Local development seeds one test user per role (`cbo_test_user`, `vetting_test_user`, `admin_test_user`), all with the
password you set as `SEED_TEST_PASSWORD` in `.env`. Build the app against the local API (`10.0.2.2` is your computer as the
emulator sees it):

```bash
cd client
./gradlew installDebug -PapiBaseUrl=http://10.0.2.2:5000/
```

Without `-PapiBaseUrl` the app uses the live Azure API. Backend tests: `dotnet test api.Tests`. Android unit tests:
`cd client && ./gradlew testDebugUnitTest`.

## Accounts on the live deployment

There is no self-registration and no seeded test user on Azure. The first Admin is created from configuration at start-up,
and that Admin creates every other account. See [docs/user-accounts.md](docs/user-accounts.md).

## More

[docs/testing.md](docs/testing.md) · [docs/security/security-review.md](docs/security/security-review.md) ·
[docs/android-release.md](docs/android-release.md) · [docs/performance/low-connectivity.md](docs/performance/low-connectivity.md)
