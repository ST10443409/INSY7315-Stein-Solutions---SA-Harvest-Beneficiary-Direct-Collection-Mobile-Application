#!/usr/bin/env bash
# Runs the low/no-connectivity scenarios (#55) on a running emulator against the local backend, through a throttled
# link, and prints the timings. Results and how to read them: docs/performance/low-connectivity.md.
#
#   docker compose up -d                                          # the backend, on :5000
#   dotnet run docs/performance/throttle-proxy.cs -- gsm          # another terminal: a 2G link on :5001
#   bash docs/performance/run-connectivity-scenarios.sh gsm 5001  # label for the logs, and the port to go through
#   bash docs/performance/run-connectivity-scenarios.sh full 5000 # straight to the backend, unthrottled
#
# Needs a running emulator, adb on PATH (or ADB=...), JAVA_HOME pointing at a JDK 17-21, and SEED_TEST_PASSWORD in the
# repo's .env. The throttling is done by throttle-proxy.cs because the emulator's own network profiles
# (-netspeed/-netdelay, `adb emu network speed`) are not enforced by emulator 37.x.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
adb="${ADB:-adb}"
label="${1:-full}"
port="${2:-5000}"

password="$(grep -E '^SEED_TEST_PASSWORD=' "$repo/.env" | cut -d= -f2- || true)"
[ -n "$password" ] || { echo "Set SEED_TEST_PASSWORD in $repo/.env (see .env.example)" >&2; exit 1; }
curl -fsS -o /dev/null --max-time 30 "http://localhost:$port/api/health" ||
  { echo "Nothing answering on :$port (docker compose up -d; and the throttle proxy for a port other than 5000)" >&2; exit 1; }

trap '"$adb" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true' EXIT

echo "=== $label (via :$port)"
"$adb" logcat -c
status=0
(cd "$repo/client" && ./gradlew -q :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.client.sync.ConnectivityScenarioTest \
    -Pandroid.testInstrumentationRunnerArguments.e2eBaseUrl="http://10.0.2.2:$port/" \
    -Pandroid.testInstrumentationRunnerArguments.e2ePassword="$password" \
    -Pandroid.testInstrumentationRunnerArguments.e2eProfile="$label") || status=$?
"$adb" logcat -d -s ConnectivityScenario:I | { grep 'scenario=' || true; } | sed -E 's/^.*ConnectivityScenario: //'
# A crash or ANR during the run is a finding even if every scenario passed.
"$adb" logcat -d | grep -E 'FATAL EXCEPTION|ANR in za.org.saharvest.collectionvetting' || echo "(no crash or ANR in logcat)"
[ "$status" -eq 0 ] || echo "!!! scenarios FAILED on $label (report: client/app/build/reports/androidTests/connected/)"
exit "$status"
