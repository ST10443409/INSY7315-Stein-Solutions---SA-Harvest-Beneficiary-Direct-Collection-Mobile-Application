# Testing Guide

## Backend
To run backend tests locally:
```bash
cd api
dotnet test
```

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
