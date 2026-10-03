# Security review: secrets, token storage and transport (#54)

Pre-release review of secret handling, Android token storage and transport security, re-checking decisions made
earlier under time pressure (#27 token storage, #29/#30 secret handling, #31 HTTPS). Reviewed on 2026-10-02 against
`main` at `d490f03`, by someone other than the original implementers of #27/#30.

**Outcome.** Two checks failed and were fixed in this change: HTTPS was not enforced anywhere, and a dev-only password
was committed. The rest passed or were hardened further. Each finding below gives what was checked, how, and what
changed. Items that need a product decision or are larger than this review are listed under
[Follow-up issues](#follow-up-issues) and filed as #70-#73.

| # | Check | Result | Severity | Status |
|---|---|---|---|---|
| S1 | No hardcoded secrets in the tree or history | One dev-only password committed (`SEED_TEST_PASSWORD`) | High | Fixed (removed, rotated, guarded) |
| S2 | JWT key and DB connection string come from env vars / secret store | Passed for dev. No staging/production exists yet (#58) | n/a | Passed, plus startup guards |
| S3 | HTTPS enforced end to end | **Failed**: `UseHttpsRedirection` was never added; no HSTS, no proxy handling | High | Fixed |
| S4 | Android token storage is Keystore-backed, no plaintext fallback | Passed | n/a | Passed; library deprecation noted |
| S5 | Login brute force | No rate limit on `POST /api/auth/login` | Medium | Fixed |
| S6 | Beneficiary data in device backups | Room DB and photos/signatures were in Google cloud backup and device transfer | Medium | Fixed |
| S7 | Release build could ship a plain-HTTP API address | Default `http://10.0.2.2:5000/` was used for every build type | Medium | Fixed |
| S8 | Network logging in release builds | URLs (with search terms such as a username) logged to logcat | Low | Fixed |
| S9 | Request bodies (incl. compressed) bounded | Anonymous login accepted up to 30 MB | Low | Fixed |
| S10 | Dev stack exposed on the LAN | Postgres and the Foodspace simulator (unauthenticated fault injection) on all interfaces | Low | Fixed |
| S11 | Secret scanning in CI | None (GitHub secret scanning is not available on this private repo) | n/a | Added gitleaks |

## S1. Hardcoded secrets

**How.** Every commit on every branch (51 commits, `git log --all -p`) was searched for added lines that assign
passwords, keys, tokens, connection strings, private keys and well-known credential formats (AWS `AKIA…`, GitHub
`ghp_`/`github_pat_`, Slack `xox…`, Google `AIza…`, Stripe `sk_live`), and every file ever committed was checked for
names that usually hold secrets (`.env*`, `appsettings*.json`, `local.properties`, keystores, `*.pem`/`*.p12`,
`google-services.json`). gitleaks was not installed locally; it now runs in CI (S11).

**Found.**

- **`SEED_TEST_PASSWORD=Dev-…` in `.env.example`** (added in `d90c094`, 2026-09-29). It is the shared password of the
  three seeded test accounts, one of which is `admin_test_user`. It was meant for local development only, but
  `compose.yaml` turned seeding on while the API ran as *Production*, so anyone deploying with compose would have had an
  Admin account with a password that is in the repository. The value stays in git history for good, so it is treated as
  public.

**Clean (documented false positives).** `api.Tests/ApiFactory.cs` (test-only signing key and password),
`leaky-db-password` (a canary that tests prove never reaches a response), `Password=x` in `RoomParityTests` (never
connects), the `SET_VIA_USER_SECRETS` placeholder in `appsettings.Development.json`, and `UserSecretsId` in
`api.csproj` (a folder name, not a secret). The removed `Backend-ASPNET/` scaffold's `appsettings*.json` held no
secrets. `local.properties`, `.env` and keystores were never committed.

**Changed.**

- `.env.example` no longer carries a value; it says the old one is public.
- `TestUserSeeder` resets the seeded accounts' password to the configured one on every start, so changing
  `SEED_TEST_PASSWORD` really retires the old password on existing development databases (`TestUserSeederTests`).
- The API refuses to start with `Seed:Enabled` outside Development (`SecurityStartupChecks`), and `compose.yaml` now
  declares itself the Development stack.

**Action for every developer:** set a new `SEED_TEST_PASSWORD` in your `.env` (e.g. `openssl rand -base64 18`) and
restart the API (`docker compose up -d api`).

## S2. Where the JWT signing key and connection string come from

| Environment | JWT signing key | Connection string | Foodspace API key |
|---|---|---|---|
| Local `dotnet run` | `dotnet user-secrets` (`Jwt:SigningKey`) | `dotnet user-secrets` (`ConnectionStrings:Default`); the committed value is a placeholder | user-secrets, empty while the simulator stands in |
| Local Docker (`compose.yaml`) | `JWT_SIGNING_KEY` in the gitignored `.env`; compose refuses to start without it | Built from `POSTGRES_*` in `.env` | `FOODSPACE_API_KEY` in `.env`, empty by default |
| CI (`cd-backend.yml`) | Test-only value in `ApiFactory`; no real secrets needed | In-memory database | Not used |
| Staging / production | **Does not exist yet** (#58). Must come from the platform's secret store (App Service / Container Apps settings backed by Key Vault), as env vars `Jwt__SigningKey`, `ConnectionStrings__Default`, `Foodspace__ApiKey` | | |

Nothing in `appsettings.json` is secret. What is enforced in code, so a future environment cannot get it wrong
silently:

- `JwtOptions` refuses to start without a signing key of at least 32 characters (existing, #30).
- The connection string is required at start-up (existing, #29).
- New (`SecurityStartupChecks`): outside Development the API refuses to start with test accounts enabled, with a
  non-HTTPS Foodspace address, or with a database connection that is not encrypted (`SSL Mode=Require`/`VerifyCA`/
  `VerifyFull`; Npgsql's default `Prefer` silently falls back to plain text), unless the database is on the same host.

## S3. HTTPS end to end

**Found.** #31 was assumed to have added `UseHttpsRedirection`; it had not. The API had no HTTPS enforcement, no HSTS
and no handling of a TLS-terminating proxy. Behind Azure's front end (#58) it would have answered plain HTTP.

`UseHttpsRedirection` is also the wrong tool for an API: by the time a client follows the redirect, it has already sent
its bearer token or password in plain text, and the middleware silently does nothing when it cannot work out the HTTPS
port, which is the reverse-proxy misconfiguration the issue warns about.

**Changed (backend).**

- `Security:RequireHttps` (on by default; off only in `appsettings.Development.json`): a plain-HTTP call is **refused**
  with `403 HTTPS_REQUIRED`, never redirected. 403 rather than 400 because the app treats 403 as "not the record's
  fault, try later", so a server misconfiguration can never mark records as failed. `/api/health` stays reachable over
  HTTP for platform probes; it reveals nothing.
- HSTS (`max-age` 365 days) on HTTPS responses.
- Forwarded headers: `X-Forwarded-Proto`/`-For` are believed only from `Security:KnownNetworks` (and loopback), so a
  client cannot claim HTTPS or another address. A misconfigured proxy now fails loudly (every call refused) instead of
  silently. The start-up log says whether HTTPS is required.
- Backend to Foodspace and backend to Postgres must be encrypted too (S2).
- Tests: `TransportSecurityTests` (plain HTTP refused without a redirect, HTTPS served with HSTS, health over HTTP,
  known proxy believed, unknown proxy ignored, start-up refusals).

**Changed (Android).**

- Release builds: `res/xml/network_security_config.xml` refuses cleartext and trusts only the system's certificate
  authorities, not user-installed ones (the usual way to intercept an app's traffic). Debug builds keep plain HTTP to
  `10.0.2.2`/`localhost` only.
- The build fails for a release variant unless `-PapiBaseUrl` is an `https://` address (S7).

**Not done: certificate pinning.** The production host and its certificate chain are not decided yet (#58), and
Azure-managed certificates rotate. Pinning now would risk locking every installed app out. Revisit once #58 settles, by
pinning the issuing CA with a backup pin.

**#58 must set:** `Security__KnownNetworks__0` to the ingress range (or `0.0.0.0/0` and `::/0` when the container has no
public port and the platform gives no fixed range), `AllowedHosts` to the API host name, and the TLS connection string.

## S4. Android token storage

**Checked.** `AuthModule` binds `TokenStorage` to `EncryptedTokenStorage`, the only implementation in `src/main`. It uses
`EncryptedSharedPreferences` with a Keystore master key (`AES256_GCM`), keys encrypted with AES-256-SIV and values with
AES-256-GCM. On a Keystore or decryption failure it deletes the file and recreates it **encrypted**; it never falls back
to plain storage. The token is never logged (`LoginResponse.toString()` and `LoginUiState.toString()` leave it out; the
HTTP log redacts `Authorization` and now only exists in debug builds).

The only other `SharedPreferences` file is `vetting_records_meta` (when the vetting list was last fetched and whether it
was stale). It holds no token or personal data; that is acceptable and documented in `PrefsRecordsMetaStore`.

**On a device** (debug build, signed in as `cbo_test_user`):

```bash
adb shell run-as za.org.saharvest.collectionvetting ls shared_prefs
adb shell run-as za.org.saharvest.collectionvetting cat shared_prefs/auth_secure_prefs.xml
```

The token, role, CBO and username are stored only as ciphertext; no `eyJ…` (JWT) appears.
See [Verification on the emulator](#verification-on-the-emulator) for the output.

**Note.** `androidx.security:security-crypto` (used at `1.1.0-alpha06`) has been deprecated upstream. It still works and
is not a vulnerability, but it will get no fixes. A follow-up moves the token to a Keystore-wrapped Tink AEAD (or
DataStore with the same) when convenient.

## S5-S10. Other findings fixed

- **S5 Login rate limit.** `POST /api/auth/login` allows 10 attempts per client address per minute
  (`Security:LoginPermitLimit`, `LoginWindowSeconds`); over that it answers `429 TOO_MANY_REQUESTS` with `Retry-After`.
  The app says "Too many sign-in attempts. Wait a minute, then try again." Per address rather than per account, so an
  attacker cannot lock a user out; collectors behind one mobile carrier NAT share a budget, which 10 a minute allows for.
  Behind a proxy the client's address comes from `X-Forwarded-For`, so `Security:KnownNetworks` must be set (S3) or every
  user shares the proxy's budget; a missing setting also fails every call with `HTTPS_REQUIRED`, so it cannot go unnoticed.
  Tests: `LoginRateLimitTests`, `AuthRepositoryImplTest`, `LoginViewModelTest`.
- **S6 Backups.** `allowBackup` is off and the backup/data-extraction rules exclude everything. The database holds
  beneficiary and donor details, and the attachments folder holds signatures and photos; these were going to the user's
  personal Google Drive backup and to a new phone on device transfer. Consequence: unsynced records do not move to a new
  phone, so sync before changing phones.
- **S7 Release API address.** A release build without `-PapiBaseUrl=https://…` now fails (`preReleaseBuild`), instead of
  shipping `http://10.0.2.2:5000/`, which the release network config would refuse anyway.
- **S8 Release logging.** The OkHttp logging interceptor is only added to debug builds.
- **S9 Body limits.** The app now sends compressed sync batches (#55), which the API decompresses. Limits apply to the
  *decompressed* size, so a small "zip bomb" cannot expand: login 16 KB, sync endpoints 4 MB (`413 PAYLOAD_TOO_LARGE`).
  Tests: `RequestCompressionTests`.
- **S10 Dev stack.** `compose.yaml` publishes Postgres and the simulator on `127.0.0.1` only. The simulator's
  `/simulate/outage` endpoints have no authentication.

## S11. Secret scanning in CI

`.github/workflows/secret-scan.yml` runs gitleaks (pinned version, checksum-verified) on every pull request and push to
`main` (new commits only, so a finding points at the change that made it) and weekly over the whole history.
`.gitleaks.toml` holds the reviewed exceptions above, each with its reason; the retired `SEED_TEST_PASSWORD` commit is
one of them. `--redact` keeps any finding's value out of the workflow log.

Reviewed exception added with the Azure setup: the three built-in role definition ids in `infra/main.bicep` (`roleKeyVaultSecretsUser`,
`roleKeyVaultSecretsOfficer`, `roleStorageBlobDataContributor`). They are public constants, identical in every Azure tenant, and the
generic-key rule flagged them only because the variable names contain "Key" (Key Vault). Matched by line so the exception covers
nothing else in the file.

Second reviewed exception, found by the first CI scan of the Azure pull request: an example image tag (`ghcr.io/owner/saharvest-api:` followed by
12 hex characters) in a workflow input description in commit `a6f48b9`, flagged for its entropy. The example was reworded in a later commit,
but CI scans every commit of a pull request on its own and history is not rewritten for a harmless string, so the exception is tied to that one
commit AND that one file (`condition = "AND"`, like the retired seed-password commit above) and covers nothing else.

## Verification on the emulator

Run on 2026-10-02 (`Pixel_10_Pro` AVD, Android 17, debug build of this change), signed in as `cbo_test_user`:

- `shared_prefs/` holds only `auth_secure_prefs.xml`. Every entry name and value in it is ciphertext: the four session
  values (token, role, CBO, username) are AES-GCM blobs, and the two Tink keysets are themselves encrypted by the
  Keystore master key. Searching the file for the JWT prefix (`eyJ`), the username, the role or the CBO id finds nothing.
- `dumpsys package com.example.client` no longer lists `ALLOW_BACKUP`.
- The login request is not compressed (60 bytes, under the 1 KB threshold); sync batches are (`Content-Encoding: gzip`).

Backend, against the Docker stack and the in-memory test host (`dotnet test api.Tests`, 366 tests passing):

```bash
curl -i http://<host>/api/auth/me                      # with Security:RequireHttps on: 403 {"error":{"code":"HTTPS_REQUIRED"}}
curl -i -H 'X-Forwarded-Proto: https' http://<host>/... # from an address outside Security:KnownNetworks: still 403
for i in $(seq 11); do curl -s -o /dev/null -w '%{http_code} ' -X POST http://<host>/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}'; done   # 401 x10, then 429
```

## Follow-up issues

Filed for release sign-off (each needs a decision or is larger than this review):

1. **Unsynced records sync under the next user's account after sign-out** (#70). Sign-out keeps PENDING records (correctly:
   they must not be lost), but the next person to sign in on that phone sends them with *their* token. The server
   attributes a collection to the token's CBO and submitter, and a decision to the token's officer, so the audit trail
   names the wrong person. Options: block sign-out while records are pending, or stamp each record with its author
   and only sync a user's own records. Needs a product decision on shared phones.
2. **Encrypt the local database at rest (SQLCipher)** (#71). The Room database (beneficiary details) and attachments rely on
   the app sandbox and Android file-based encryption. That protects a locked phone, but not a rooted one or a forensic
   extraction from an unlocked one. Decide with SA Harvest whether POPIA obligations for beneficiary data require it.
3. **Replace the deprecated `security-crypto` library** (#72; S4).
4. **Certificate pinning** once the production host is decided (#73; S3, after #58).
5. **#58 deployment settings**: `Security__KnownNetworks`, `AllowedHosts`, TLS connection string, Key Vault-backed
   secrets (S2, S3). Added to #58 as a comment rather than a new issue.
