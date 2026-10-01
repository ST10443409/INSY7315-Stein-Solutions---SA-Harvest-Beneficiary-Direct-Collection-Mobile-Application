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
