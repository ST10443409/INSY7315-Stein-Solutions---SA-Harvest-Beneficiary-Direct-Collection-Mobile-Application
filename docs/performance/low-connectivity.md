# Performance under low and no connectivity (#55)

The app is offline first: collectors and vetting officers save records on the phone, and background workers send them
when there is a connection. This tested that premise under the conditions it was built for: no connection at all, a
connection that drops in the middle of a sync, and slow 2G/3G links. Run on 2026-10-02 on the `Pixel_10_Pro` emulator
(Android 17, emulator 37.1) against the real backend in Docker, at `main` `d490f03` plus this change.

## Summary

| Acceptance criterion | Result |
|---|---|
| Airplane mode: records saved offline are present and `PENDING` after reconnecting, nothing lost | **Pass**: 120 of 120 kept, all `PENDING`, no retry used; all synced once the network was back |
| Connection lost mid-sync: resumes and completes, no inconsistent state | **Pass**, in both variants (lost while uploading; lost after the server stored a batch but before the answer arrived). No duplicates |
| Throttled 2G/3G: sync completes without freezing, ANR or crash | **Failed before this change** (on GSM no batch could ever finish). **Pass after it**: 100 records in 13 s on GSM (about 3x faster than before on EDGE and UMTS); the main thread was never more than 33 ms late; no crash or ANR |
| Issues found are filed separately | Fixes are separate commits in this change; see [Findings](#findings) and [Follow-up issues](#follow-up-issues) |

## How it was tested

**Automated, every build** (`client/app/src/test/.../sync/SyncUnderPoorConnectivityTest.kt`). The real sync processor and
the production OkHttp set-up (`HttpClients`) against a stand-in backend over a real socket (MockWebServer). It covers
no connection, the connection dropping while uploading, the connection dropping after the server stored a batch, a
stalled connection, and a throttled uplink. Runs in CI with the other unit tests, in about 2 s.

**On the emulator, against the real backend** (`client/app/src/androidTest/.../sync/ConnectivityScenarioTest.kt`). It
saves collections through the real repository into a real Room database and syncs them with the real processor, toggling
airplane mode from inside the test the way a user would. It needs the backend and a test password, so CI skips it. To
run it:

```bash
docker compose up -d
dotnet run docs/performance/throttle-proxy.cs -- gsm        # in another terminal: a GSM link on :5001
bash docs/performance/run-connectivity-scenarios.sh gsm 5001     # or: full 5000 (straight to the backend)
```

**Throttling.** The issue planned to use the emulator's network profiles (`-netspeed gsm -netdelay gsm`,
`adb emu network speed`). **Emulator 37.1 does not enforce them** (finding P1): `adb emu network status` reports the
profile, but the round trip to the host stayed under 2 ms and 115 KB uploaded in about 0.1 s on "gsm". That was the
same over Wi-Fi and over the emulated mobile network, and the same with the flags given at start-up on a fresh AVD. The
first runs below therefore show no difference between profiles. `docs/performance/throttle-proxy.cs` does the
throttling on the host instead. It is a small TCP proxy between the emulator and the API that applies the emulator's
own numbers for each profile (bandwidth each way, round-trip latency range) and buffers 64 KB per direction, like a
phone's socket buffer in front of a slow radio. It works on any machine and any emulator version.

| Profile | Up / down | Round trip |
|---|---|---|
| `gsm` | 14.4 / 14.4 kbit/s | 150-550 ms |
| `edge` | 473.6 / 473.6 kbit/s | 80-400 ms |
| `umts` | 384 / 384 kbit/s | 35-200 ms |

## Results

Each scenario saves Form 1 collections with three product lines each, shaped like real ones (donor, notes, delivery
note, GPS), then syncs them in batches of 50, as the app does.

### No connection (airplane mode), 120 records

| | Full speed | UMTS | EDGE | GSM |
|---|---|---|---|---|
| Save 120 collections while offline | 360 ms | 285 ms | 522 ms | 180-465 ms |
| Sync attempt while offline | gives up in 77 ms | 84 ms | 78 ms | 32-64 ms |
| Records after the attempt | 120 `PENDING`, 0 retries used | same | same | same |
| Sync once the network is back | all 120 in 0.1 s | all 120 in 1.4 s | all 120 in 2.0 s | all 120 in 16.3-16.5 s |
| Server check (every record re-sent) | all "already received": each stored once | same | same | same |

Saving costs 1.5-4.4 ms per collection with its product lines, whatever the network.

### Connection lost in the middle of a sync, 150 records (three batches)

| Variant | First run | After reconnecting |
|---|---|---|
| Airplane mode turns on halfway through uploading batch 2 | `RETRY_LATER`; batch 1 synced; 100 `PENDING`, 0 retries used | all 150 synced, each stored once |
| The server stores batch 2, then the network goes before its answer arrives | `RETRY_LATER`; batch 1 synced; 100 `PENDING` | all 150 synced; batch 2 answered "already received", no duplicates |

Same result on every profile. The second variant is the worst case for consistency: the phone cannot know batch 2
arrived. The client-generated UUID and the server's "first write wins" idempotency (#36) handle it. Server side, across
every run of every scenario: 5,460 records, 5,460 distinct ids, 0 flagged as duplicates, all forwarded to Foodspace.

### Throttled link, 100 records (two batches)

"Before" is the pre-#55 set-up (OkHttp defaults: 10 s timeouts, no compression); "after" is `HttpClients` (60 s
timeouts and gzip). The middle rows apply each change on its own.

| | GSM | EDGE | UMTS | Full speed |
|---|---|---|---|---|
| **Before** | **gave up after 10.4 s, 0 of 100 synced** (read timeout) | 100 in 5.0 s | 100 in 4.5 s | 100 in 0.11 s |
| Timeouts only | 100 in 70.3 s | 100 in 5.0 s | 100 in 4.7 s | |
| Gzip only | 100 in 13.6 s | 100 in 1.8 s | 100 in 1.4 s | |
| **After** | **100 in 13.2 s** | **100 in 1.8 s** | **100 in 1.5 s** | 100 in 0.08 s |
| Uploaded, before / after | 57.8 KB (then gave up) / 16.2 KB | 115.5 / 16.4 KB | 115.5 / 16.4 KB | 115.5 / 16.5 KB |
| Main thread, worst delay | 17-33 ms | 17-18 ms | 17-32 ms | 5-8 ms |

Compression is what makes 2G usable (5x faster than the longer timeouts alone) and is about 3x faster on EDGE and
UMTS too. The longer timeouts are headroom for batches that compress less, such as ones with long notes. No
`FATAL EXCEPTION` and no ANR in logcat during any run. Login took 0.9-1.4 s on every throttled profile.

Host-side check of one batch of 50 (58.7 KB of JSON, 8.6 KB gzipped) through the GSM proxy with `curl`: 39.4 s as plain
JSON, 10.6 s gzipped.

### The real app, by hand, on GSM

The debug app built against the GSM proxy (`-PapiBaseUrl=http://10.0.2.2:5001/`), driven through its screens:

| Step | What happened |
|---|---|
| Sign in as `cbo_test_user` | Login request 0.5-0.8 s; home screen shows "Online · syncing in background" |
| Airplane mode on; Form 1 filled in (product, donor, both signatures, a gallery photo, delivery note) and completed | "Collection recorded: saved on the device and queued"; Sync tab: "1 waiting to be sent", "Offline · working from the device", record `Pending` |
| Airplane mode off | `CboSyncWorker` started on its own; the record (547 bytes gzipped) was sent and answered `200` in 0.98 s; synced **4 s after the network came back**; Sync tab: "Everything on this device is on the server" |
| Airplane mode on; a second collection saved; **signed out**; airplane mode off | `CboSyncWorker` ran and finished **without any request** (before this change it would have uploaded the batch to get a 401) |
| Signed back in | Sync request 1 s after the login answer; record synced **2.4 s after tapping Sign in** (before: up to 15 minutes) |
| Server | Both collections stored once, under `cbo_test_user` and `cbo-test-001`, forwarded to Foodspace |

No crash or ANR. A cold start of this debug build took 2.5-3.5 s on the emulator (software rendering, debug build:
not representative of a release build on a phone; not investigated further).

## Findings

**P1. The emulator's network profiles are not enforced by emulator 37.1** (tooling, not the app). Testing with them
shows full speed whatever the profile, so it looks like the app copes with 2G when it was never tested on it. Worked around
with `throttle-proxy.cs`. Worth reporting upstream; meanwhile use the proxy.

**P2. On a 2G link, no sync batch could ever finish (fixed).** A batch of 50 collections is about 59 KB of JSON, which
takes about 33 s to upload at GSM speed. OkHttp accepts the whole body into the socket buffer at once, then waits for the
answer with its default 10 s read timeout, which expires while the upload is still draining. The batch failed, WorkManager
retried with backoff and failed the same way 5 times, then the periodic run 15 minutes later did the same. Records were
never lost (they stayed `PENDING`), but on a GSM connection they would never have synced. Measured above: before, 0 of 100
in 10.4 s; after, 100 of 100 in 13.2 s. Fixed by:
- **Compressing request bodies** (`GzipRequestInterceptor`): a batch of 50 goes from about 59 KB to about 9 KB (85% less
  with realistic data), so it uploads in about 5 s on GSM instead of 33 s. The backend decompresses (`UseRequestDecompression`),
  with size limits applied after decompression (#54). Less data also means less cost to collectors on prepaid data.
- **Timeouts sized for 2G** (`HttpClients`): 20 s to connect, 60 s to read or write, so a batch with long notes, which
  compresses less, still fits.

**P3. Records synced in the wrong role, and while signed out (fixed).** The JWT expires after 60 minutes (#30), so a
collector working offline for a morning comes back to an expired session. The workers kept sending, got 401s, and the
session ended. Signed out, or signed in under a role the endpoint refuses, every periodic and retried run still uploaded a
full batch only to be refused, costing data each time. The workers now skip the network when nobody who may send those
records is signed in (`BaseSyncWorker`), and signing in queues a sync at once (`MainActivity`). Before, records saved under
an expired session waited up to 15 minutes after signing back in.

**P4. An app update could silently delete unsynced records (fixed).** `DatabaseModule` used
`fallbackToDestructiveMigration()`, marked "replace before release": any update that changed the schema without a
migration would have wiped the database, including `PENDING` records that never reached the server. Every migration from
the first released schema (v2) exists and is tested, so the fallback now only applies to the pre-release v1.
`MigrationsTest` fails the build if a schema version is added without a migration.

**Checked and fine.**
- Saving is local and fast whatever the network: 1.5-4.4 ms per collection with its product lines.
- With no connection the sync gives up in 30-80 ms (Android reports the network unreachable at once), so it never hangs.
  WorkManager only starts it with a network available (`NetworkType.CONNECTED`) anyway.
- Connectivity problems never use up a record's retries or mark it `FAILED`; only the server's answer about the record
  does (#40).
- Sync never runs on the main thread: the worst main-thread delay while syncing was 33 ms (two frames), against
  5 s for an ANR.

**Not covered here.**
- The vetting records download (`GET /api/vetting/records`) was not load-tested on 2G: the Foodspace simulator has only
  3 beneficiaries. It fetches pages of 100 and keeps the old list if any page fails. With a real list of thousands, one
  failed page on a flaky connection throws away the pages already fetched (follow-up 1).
- Photos and signatures are uploaded now (one request per file, after the record they belong to), and verified on the emulator
  over the normal link, but **not yet timed on 2G**: test them the same way. One photo (up to 1600 px, JPEG 85) is far larger
  than a whole batch of records, and images are sent uncompressed on purpose (JPEG and PNG are compressed already).

## Follow-up issues

1. **Vetting records refresh: resume or retry a failed page** instead of discarding the pages already downloaded, once a
   realistic list size is known from Foodspace (#74).
2. **Test attachment upload on 2G** (#75). Upload is built as separate requests, one per file, each retried from the start
   after a drop; if a photo cannot finish within the 60 s socket timeouts on 2G, make it resumable or chunked.
3. **Report the emulator network-profile regression** (P1) to the Android emulator issue tracker (upstream, not this
   repository).
