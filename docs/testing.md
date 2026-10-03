# Testing Guide

## Backend
To run backend tests locally:
```bash
cd api
dotnet test
```

### Tests that need a real PostgreSQL
Most backend tests run the API in memory and need nothing. `api.Tests/PostgresClaimAndMigrationTests.cs` needs a real
PostgreSQL: it applies every migration to an empty database (so a forgotten migration, or one PostgreSQL rejects, fails here
and not in Azure) and checks that the Foodspace forwarding claim holds when several workers run at once. Without
`TEST_POSTGRES` set those tests are **skipped** (reported as skipped, not passed). CI starts a PostgreSQL service container
and always sets it. To run them locally (each test makes and drops its own throwaway database):
```bash
docker run -d --name sah-test-pg -e POSTGRES_HOST_AUTH_METHOD=trust -p 127.0.0.1:55432:5432 postgres:17-alpine
TEST_POSTGRES="Host=127.0.0.1;Port=55432;Username=postgres;Database=postgres" dotnet test api.Tests
docker rm -f sah-test-pg
```
When you add a migration, generate it with `dotnet ef migrations add <Name> --project api` (set
`ASPNETCORE_ENVIRONMENT=Development` and `Jwt__SigningKey` to any 32+ character value for the design-time run), keep it
additive and nullable where a deployment may overlap the previous version, and run these tests.

## Android
To run Android unit and instrumented tests locally:
```bash
cd client
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

## Low and no connectivity (#55)
The unit tests above include the sync under no connection, dropped connections and a throttled link
(`SyncUnderPoorConnectivityTest`). The same scenarios on an emulator, against the real backend and through a 2G link,
are opt-in because they need the backend and a test password:
```bash
docker compose up -d
dotnet run docs/performance/throttle-proxy.cs -- gsm        # in another terminal
bash docs/performance/run-connectivity-scenarios.sh gsm 5001
```
Results and how to read them: [`docs/performance/low-connectivity.md`](performance/low-connectivity.md).

## Security checks (#54)
Secret scanning runs in CI (`.github/workflows/secret-scan.yml`). The transport and token-storage checks, and how to
repeat them on a device, are in [`docs/security/security-review.md`](security/security-review.md).
