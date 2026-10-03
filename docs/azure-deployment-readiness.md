# Azure deployment readiness (gap analysis)

Written 2026-10-03 against `main` at `ce7954d`, using the *SA Harvest: Systems Design and Architecture* document (Part B: B6.4,
B6.5, B8.3, B9, B10, B11) as the target. Covers the backend (`api/`), the Android app (`client/`) and the pipelines
(`.github/`). Existing tracking issues: #57 monitoring, #58 Azure deployment, #59 release build and signing, #62
distribution, #63 load/smoke test, #71 DB encryption, #73 certificate pinning, #75 photo upload on 2G.

Status key: **Done** (in the repo and tested), **Partial**, **Missing**, **Decision** (needs an answer from the team or
SA Harvest before it can be built).

## Decisions taken (2026-10-03) and what they change

These override the matching rows further down (sections 2 and 8 were written before they were made).

| Decision | Consequence |
|---|---|
| **Free tiers / student plan** | No deployment slots (they need Standard), so blue-green swap from B6.5 is dropped: deploy the SHA-tagged image, health-check it, roll back by redeploying the previous tag. **Open risk:** the Free (F1) App Service has no Always On, so the process sleeps when idle and the Foodspace forwarding loop (which retries on a timer) stops with it; F1 also has a daily CPU quota. Whether a *container* can run on F1 must be checked in the portal. Basic (B1, about US$13/month) is the realistic floor and is covered by the student credit. Azure Container Registry has no free tier (Basic, about US$5/month); GitHub Container Registry is free for private images and App Service can pull from it with a token. Key Vault (Standard, pay per operation), Application Insights (5 GB/month free) and Blob (5 GB free for 12 months) are effectively free at this scale. |
| **Database is SA Harvest's, not Azure's** | No Azure PostgreSQL. The API still needs `SSL Mode=Require` or stronger and **their** firewall must admit the App Service's outbound addresses (shared, can change; a fixed address needs VNet integration plus NAT, which is not free). EF migrations would create this API's tables (users, sync state, admin actions) in their database, so they must agree to that and give a database role that may. Information needed from SA Harvest is listed in the reply that accompanies this change. |
| **No APIM / WAF / private endpoints** | Record the risk acceptance (document B8.3 and B9.1 describe them). Compensating controls already in the API: HTTPS-only, rate limit, role checks. Add App Service access restrictions if SA Harvest has fixed office/CBO ranges (they likely do not, since collectors use mobile data). |
| **Application id** | **Confirmed:** `za.org.saharvest.collectionvetting`; display name `SAH Collection & Vetting` (the `app_name` string, `&amp;` in XML). Not yet changed in `build.gradle.kts`: it goes in with the Android release configuration. It cannot change after the first APK is installed. |
| **Region: Spain Central** (`spaincentral`) | Chosen over South Africa North because South Africa's region offers fewer services. Three things to know. (1) Beneficiary data (POPIA personal information) would then be processed outside South Africa; POPIA section 72 permits that where the recipient country has comparable protection (Spain is under the EU's GDPR) or the data subjects consent, but SA Harvest should confirm and the choice belongs in the handover documents. (2) Round trips from South Africa to Spain are several times longer than to South Africa North, on top of the 2G/3G links the app already budgets for (timeouts in `HttpClients` are generous, but record it in #63's load test). (3) An Azure for Students subscription limits which regions can be used; confirm Spain Central is allowed on yours before any resource is planned around it. Keep every resource (app, storage, Key Vault, registry) in the same region. |
| **Branch rules** | Pro is active on `ST10443409`. The owner may bypass protection (`enforce_admins: false`). Emulator tests skip on `development` pull requests (accepted). |

## 0. Where things stand

| Area | Status | One-line summary |
|---|---|---|
| Container image | **Built, not yet deployed** | Multi-stage, non-root, .NET 10. `deploy.yml` publishes it to GitHub Container Registry (free) tagged with the commit. |
| Key Vault | **Defined, not yet created** | `infra/main.bicep` creates the vault and the Web App reads secrets through Key Vault references (no code change). Secret values are set by hand (`infra/README.md`). |
| Blob Storage | **Missing (largest piece of work)** | The API has no photo/signature endpoint, no storage code, no Azure packages. The app keeps the files on the device and its upload worker is not built (#75). |
| Security hardening of the API | Done | HTTPS-only, HSTS, forwarded headers, login rate limit, startup refusal of unsafe config, body limits (#54). |
| Observability | Missing | No Application Insights/OpenTelemetry, no alerts (#57). |
| CI | **Done** | Backend, Android and secret-scan workflows run for PRs into `development`/`main` and pushes to `development`; `deploy.yml` calls the same three for `main`. |
| CD | **Written, not yet run** | `deploy.yml` (publish image, deploy by repointing the `live` image tag and calling the Web App webhook (no Azure login; the school tenant blocks Entra app registrations), smoke test, auto-rollback, signed APK) and `redeploy-backend.yml`. Needs the one-time setup in `infra/README.md`; never run against a real Azure subscription yet. |
| Android release | **Mostly done** (items 1-5 and 13 of section 4, on `feat/android-release-config`) | Id `za.org.saharvest.collectionvetting`, R8 on with keep rules, signing from environment variables, version from CI, release build in PR CI, all verified on an emulator. Still open: the keystore itself (yours to create), the signed build in the pipeline, pinning, SQLCipher, app lock, signature self-check, distribution. See `docs/android-release.md`. |
| Repo governance | **Done** for `development`; environment still to create | `development` is protected (PR + 3 checks, 0 approvals, owner may bypass); `main` deliberately is not. The `production` GitHub environment and its variables are created in `infra/README.md` step 5. |

## 1. Blockers found while reading the code (fix before the first deploy)

1. **Duplicate forwarding when more than one worker runs. FIXED on `fix/backend-deploy-blockers`.** The forwarders selected
   due rows with no lock, so an overlapping deployment (old and new container briefly both running) or a second instance
   made two workers forward the same record to Foodspace, whose de-duplication is still an open question
   (`docs/OPEN-DECISIONS.md` #1). Now each send is preceded by a database-arbitrated claim (`ForwardingClaim`: a concurrency
   token plus a lease; `Foodspace:ClaimLeaseSeconds`, default 120), covering the background loop, ingestion and an Admin's
   retry alike. Proven on real PostgreSQL (four concurrent forwarders, 20 records, each sent once) and by a regression test
   for the "ABA" trap the first version fell into. Slots are not planned on the free tier, so
   `Foodspace__ForwardingEnabled=false` on a staging copy is now a precaution rather than a requirement.
2. **Deployment slots need Standard tier or above.** The design document costs B1 (R220) for 100 users *and* relies on
   slot swap (B6.5, B10.3). Basic tier has no slots. Either budget S1 (R1,190, the doc's 1,000-user figure) from day one,
   or drop slots for the pilot and roll back by redeploying the previous image tag.
3. **Legacy `/api/sync` + `QueueBackgroundWorker`. REMOVED on `fix/backend-deploy-blockers`** (controller, `QueueService`,
   `QueueBackgroundWorker`, `SyncPayload`, the `"ExternalApi"` named client; a test keeps the route gone). Left for the
   Android work: the unused `SyncApiService.syncData` declaration (five client test fakes implement it) and the Room
   `sync_payloads` table, which needs a schema version and migration.
4. **`Security:KnownNetworks`, `AllowedHosts` and the database TLS mode must be set or every call fails.** (Documented in
   `security-review.md` S3, repeated here because it is the usual first-deploy failure.) `AllowedHosts` must include the
   *staging slot* host name too (use `*.azurewebsites.net` or list both), or the slot's health check and smoke test get a 400.
5. **Release build config. DONE on `feat/android-release-config`** (section 4): application id, R8 with keep rules, signing from environment variables, CI-derived version, and a release build in every Android pull request. The release keystore and the signed pipeline build are still to do.
6. **No way to create a user account in any deployed environment. DONE on `feat/user-accounts`.** The only code that created users was the Development-only seeder. Now: the first Admin is created from configuration at start-up (`Bootstrap__AdminUsername` plus a Key Vault password; it does nothing once an Admin exists, never changes an existing account, and refuses to start on a half-set or weak configuration), and Admins manage accounts through `/api/admin/users` (create, list, get, change role/CBO/active, reset password), every change audited. Deactivating, re-roling or resetting a password ends the account's sessions at once (a per-user security stamp checked on every request), so a lost phone is not a 60-minute problem. See `docs/user-accounts.md` and `infra/README.md` section 7. Still open: an Admin screen in the app, a self-service password change, and checking a collector's CBO id against a real list.
   un-minified and named `com.example.client`.

## 2. Azure resources to provision

Provision with Bicep in `infra/` (one parameter file per environment) rather than clicking through the portal, so Test,
UAT and Production are provably the same shape (B6.5: "same images across environments").

| Resource | Notes |
|---|---|
| Resource group per environment | Region: **Spain Central** (decided; see "Decisions taken"). Confirm every SKU you want exists there. |
| Azure Container Registry | Basic (doc: R85). Admin user **off**; App Service pulls with its managed identity (`AcrPull`). Keep the last 5 tags (doc B10.3). Vulnerability scanning needs Defender for Containers (not in the doc's cost table). |
| App Service plan (Linux) + Web App for Containers | Image from ACR, `WEBSITES_PORT=8080`, health check path `/api/health`, `alwaysOn`, HTTPS only, TLS 1.2 minimum, FTPS disabled, system-assigned identity. Staging slot if S1 or above. |
| Azure Database for PostgreSQL Flexible Server | **Decision.** The document costs "FoodSpace Database integration, no separate database", but this API has its own tables (users, sync state, admin actions, vetting decisions) and its own migrations. Either SA Harvest hosts a database for it on FoodSpace's server, or you add Flexible Server (Burstable B1ms is roughly R300-400/month, not in the cost table). TLS is enforced by default on Azure; the API demands `SSL Mode=Require` or stronger. 7-day PITR backup per B6.5. |
| Storage account | StorageV2, **no public blob access**, shared-key access disabled (use Entra/RBAC), TLS 1.2, SSE (default), soft delete, one **private** container per environment (`attachments`). GRS for production (doc B6.5). Lifecycle rule for retention (a POPIA question for SA Harvest). |
| Key Vault | RBAC mode, soft delete + purge protection. Secrets: `Jwt--SigningKey`, `ConnectionStrings--Default`, `Foodspace--ApiKey`, Application Insights connection string. The web app's identity gets `Key Vault Secrets User`. |
| Log Analytics + Application Insights | Workspace-based. Alerts: 5xx rate, health check failing, p95 latency above 500 ms (doc target), forwarding queue depth/age (the admin sync-status service already computes these). |
| API Management + WAF, VNet, private endpoints | **Decision.** The document puts APIM + WAF in front and everything else private (B8.3, B9.1) but the cost table (B11.1) includes none of it. APIM Developer/Standard and Front Door WAF are each larger than the whole App Service line. For a one-CBO pilot, App Service access restrictions plus the API's own rate limiting is defensible; write the decision down. |
| Identities and RBAC | (a) Web app identity: `AcrPull`, `Key Vault Secrets User`, `Storage Blob Data Contributor` scoped to the one container. (b) GitHub deploy identity (OIDC, section 5): `AcrPush` on the registry, `Website Contributor` on the web app, nothing else. (c) A migration identity or connection string used only by the pipeline step (section 3.4). |

### App settings per environment (App Service > Environment variables)

Plain values may live in the Bicep parameters; secrets are Key Vault references (`@Microsoft.KeyVault(SecretUri=...)`).

| Setting | Value / source | Slot-sticky |
|---|---|---|
| `ASPNETCORE_ENVIRONMENT` | `Production` (UAT/QA: `Staging`). Never `Development`: it enables seeding and OpenAPI. | yes |
| `WEBSITES_PORT` | `8080` | |
| `Security__KnownNetworks__0`, `__1` | `0.0.0.0/0`, `::/0` (App Service's front end has no fixed range; safe only while access restrictions keep the site off the open internet except via the front end) | |
| `AllowedHosts` | the API host names | |
| `Jwt__SigningKey` | Key Vault reference (>= 32 chars; generate with `openssl rand -base64 48`) | |
| `Jwt__Issuer`, `Jwt__Audience` | `SAHarvest.Api`, `SAHarvest.Mobile` (already the defaults) | |
| `ConnectionStrings__Default` | Key Vault reference; `...;SSL Mode=VerifyFull` (verify the DigiCert root is in the image trust store, else `Require`) | yes |
| `Foodspace__BaseUrl`, `Foodspace__ApiKey` | real https address; key from Key Vault. Blocked on open decision #1 | yes |
| `Foodspace__ForwardingEnabled` | `true` on production, **`false` on the staging slot** (blocker 1) | yes |
| `Storage__AccountUri`, `Storage__Container` | new, for Blob (section 3.2) | |
| `APPLICATIONINSIGHTS_CONNECTION_STRING` | Key Vault reference | |
| `Database__MigrateOnStartup` | `false` (migrations run as a pipeline step) | |
| `Seed__Enabled` | never set (the API refuses to start with it outside Development) | |

## 3. Backend work

### 3.1 Key Vault (small)

- No code needed if you use App Service Key Vault references (recommended; the existing `__` env-var configuration binds
  unchanged). Add `Azure.Identity` only if you also want in-process reads.
- Add a startup check that the three secrets are present and not placeholders, so a broken reference fails loudly
  (`JwtOptions` already does this for the signing key).
- Document rotation: the JWT signing key is a single symmetric key, so rotating it signs everyone out (acceptable at 60-minute tokens).
- The design says "Azure AD issues the JWTs" (B8.3, B9.2). The code issues its own JWTs against its own user table. This is
  a **document/implementation difference to resolve explicitly**: either keep the current approach and amend the document
  (recommended: it is built, tested by the role matrix, and Entra ID would need an app registration per environment and a
  sign-in flow in the app), or schedule Entra ID as a separate piece of work.

### 3.2 Blob Storage (the big one)

Nothing exists on the API side. Build, in order:

1. **Abstraction.** `IAttachmentStore` (put, open/delete, create read-SAS) with an Azure implementation using
   `Azure.Storage.Blobs` + `Azure.Identity` (`DefaultAzureCredential`, no account key anywhere), and an in-memory fake for
   `api.Tests`. Add a health-check dependency (container reachable) without revealing detail to anonymous callers.
2. **Data model + migration.** An `CboCollectionAttachment` table (id = the client's attachment UUID, collection id, kind
   `DONOR_SIGNATURE | CBO_SIGNATURE | PHOTO | DELIVERY_NOTE`, slot, blob name, content type, size, sha256, uploader, created).
   Unique on the client id so a retried upload is idempotent (same rule as records). Update `RoomParityTests` if it checks
   attachment entities.
3. **Endpoint.** `POST /api/cbo-collection/{collectionId}/attachments` (raw or multipart, one file per call so one slow photo
   never blocks others; the Room DAO already models it that way). Rules: role `CBO_COLLECTION` (and Admin), the collection
   must exist and belong to the caller's CBO and author (reuse #70's author rule), per-kind size cap (signature a few
   hundred KB, photos 2-5 MB), allow-list `image/jpeg|png`, **verify magic bytes not the header**, strip EXIF GPS unless wanted,
   server-chosen blob name (`{env}/{collectionId}/{attachmentId}`), never the client's file name. Errors in the standard
   envelope with `retryable` semantics the app already understands (`VALIDATION_FAILED`, 413 `PAYLOAD_TOO_LARGE`; **raise the
   4 MB body limit for this route only**).
4. **Read path.** Never return raw blob URLs (A4). An Admin-only `GET .../attachments/{id}` that returns a 15-minute
   user-delegation SAS (doc A1.7), or streams through the API. Decide whether the Admin screens show photos at all in v1.
5. **Foodspace.** Decide what Foodspace receives (a reference, a SAS, the bytes). Needs an answer from the Foodspace team;
   add it to `docs/OPEN-DECISIONS.md`.
6. **Local dev + tests.** Add Azurite to `compose.yaml` (`Storage__AccountUri` pointing at it) so Development never needs a real account.
   CI runs against the fake store only.

### 3.3 Container and runtime

- Dockerfile is sound. Add: `ARG`/labels for commit SHA and build date, pin base images by digest (Dependabot can bump them),
  no `HEALTHCHECK` needed because App Service uses its own probe, `.dockerignore` for `external-api-sim/`.
- **Do not deploy `external-api-sim`.** Its fault-injection endpoints are unauthenticated (S10). At most it stands in for
  Foodspace in a Test/QA environment, behind an access restriction. CI keeps building it so it cannot rot.
- Resolve blockers 1 and 3 above.
- Reverse-proxy behaviour was designed for this (S3) but has never run behind App Service: test the first deploy with
  `curl` against the staging slot before anything else (`HTTPS_REQUIRED` means `KnownNetworks` is wrong).

### 3.4 Database migrations

- The document requires migrations as a **pipeline step before the new version starts** (B6.5). Implement with
  `dotnet ef migrations bundle` (the repo already pins `dotnet-ef` 10.0.12 in `dotnet-tools.json`): CI builds the bundle once,
  the deploy job runs it against the target database, then deploys the image.
- Because a slot swap means the *old* code briefly runs against the *new* schema, migrations must be backward compatible
  (add columns nullable, drop in a later release).
- **CI now runs the migrations (done on `fix/backend-deploy-blockers`).** The workflow starts a `postgres:17-alpine` service
  container and sets `TEST_POSTGRES`; `PostgresClaimAndMigrationTests` creates a throwaway database, applies every migration
  to it (EF refuses if the model has changes no migration covers) and asserts `HasPendingModelChanges()` is false. A forgotten
  migration, or one PostgreSQL rejects, now fails the pull request instead of failing in Azure. Locally the tests are
  skipped unless `TEST_POSTGRES` is set (see `docs/testing.md`).

### 3.5 Observability (#57)

- Add `Azure.Monitor.OpenTelemetry.AspNetCore` (connection string from settings). Keep the "no personal information in logs
  or telemetry" rule from B9.6: check request-URL logging (usernames appear in some query strings) and never enable EF sensitive-data logging.
- Alerts as listed in section 2. Decide on Sentry vs Application Insights (the document says Application Insights; #57 says Sentry).

## 4. Android: release APK readiness

Baseline `assembleRelease` result: see "Verified" at the end.

| # | Item | Detail |
|---|---|---|
| 1 | **Application id. DONE** | `za.org.saharvest.collectionvetting` (display name `SAH Collection & Vetting`), confirmed by the team. `namespace` stays `com.example.client`. |
| 2 | **Signing. DONE in the build; keystore still to create** | `signingConfigs.release` reads `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` (or the gitignored `client/keystore.properties`). `-PrequireReleaseSigning=true` fails the build rather than producing an unsigned APK. `.gitignore` now refuses `*.jks`, `*.keystore`, `keystore.properties`. **The person who owns releases must create the keystore and back it up** (steps in `docs/android-release.md`); losing it strands every installed phone. |
| 3 | **Versioning. DONE** | `-PappVersionCode` / `-PappVersionName`; the pipeline passes the run number. Local default `1` / `1.0`. |
| 4 | **R8 / minify. DONE and verified** | `isMinifyEnabled` + `isShrinkResources`, `proguard-rules.pro` created (Gson fields of the `network` package and the Room entities used as bodies, enums, Retrofit full-mode rules). APK 11.5 MB to 4.4 MB. Verified on an emulator through the non-shipping `r8Check` build type against the real API (sign-in, 3 beneficiary records downloaded and shown, a decision saved and forwarded, no crash). Not hand-driven: collector form upload and admin screens. |
| 5 | **API address. Unchanged, decided** | Baked in at build time; the release build refuses `http://`. Pass it from a GitHub variable per environment once the host exists. |
| 6 | **Certificate pinning (#73)** | Not done, deliberately. `*.azurewebsites.net` certificates are Microsoft-managed and rotate. Pin the issuing CA with a backup pin, or use a custom domain with your own certificate. Decide **before** the first APK ships, because pins cannot be added to installed apps without an update. Include an expiry-safe fallback (a pinning failure must not mark records as failed; the app already treats connectivity errors as "retry"). |
| 7 | **Local DB encryption (#71)** | The document says SQLCipher (B9.4, risk register); the code relies on Android file-based encryption. Needs a decision (POPIA, shared phones). Encrypting later requires a data migration on devices with unsynced records, so decide before rollout. |
| 8 | **App lock** | The document requires a PIN/biometric lock, especially for Admin (B9.4). Not implemented (no `androidx.biometric`). |
| 9 | **Signing-certificate self-check** | The risk register promises the app verifies its own signing certificate at launch (sideloaded tampering). Not implemented. |
| 10 | **`security-crypto` (#72)** | Deprecated; works. Fine for the pilot, schedule the replacement. |
| 11 | **Photo upload** | Android side of 3.2: a Retrofit upload method, an `AttachmentUploadWorker` using the existing DAO queries (`getUploadableAttachments`, `markAttachmentsUploaded`, `markAttachmentsRejected`), and a `GzipRequestInterceptor` exemption for already-compressed images (it currently gzips any body over 1 KB). Then #75 (test on 2G). |
| 12 | **Distribution (#62)** | The document says direct APK distribution, no store. **Decision:** where staff download it (private Blob container + short SAS link, Firebase App Distribution, or Intune/managed Google Play). Publish the SHA-256 beside every build (risk register). Android blocks installing over a differently-signed APK, which is another reason signing key custody matters. |
| 13 | **CI. DONE** | `ci-android.yml` builds the release variant (R8, `lintVitalRelease`, unsigned, dummy https address) in every Android pull request. |

## 5. Pipelines: branching, CI and CD

**Status (feat/azure-deploy): section 5.2 is now built.** `deploy.yml` calls `cd-backend.yml`, `ci-android.yml` and
`secret-scan.yml` as reusable workflows (so the deployed commit went through the PR sequence by construction), publishes the image
to GHCR, deploys through `_deploy-backend.yml` (repoint the `live` tag, call the Web App webhook, wait for the new build revision on /api/health, smoke test, automatic restore of `last-good`), and
builds the signed APK. `redeploy-backend.yml` rolls back or forward by tag. Infrastructure is `infra/main.bicep`. Deviations from
5.2 forced by the free tier: no slots (so no blue-green swap; restore-on-failure instead), GHCR instead of ACR (the registry pull
uses a token in Key Vault instead of a managed identity), migrations run at start-up instead of a pipeline step (SA Harvest's
database is probably not reachable from GitHub's runners). None of it has run against a real Azure subscription.

### 5.1 What existed before (kept for the record)

- `cd-backend.yml` (named "CI/CD" but CI only): restore, build, test (in-memory), build the two Docker images. Path-filtered, runs on PRs to `main` and pushes to `main`.
- `ci-android.yml`: build, lint, unit tests, emulator instrumented tests. Same triggers.
- `secret-scan.yml`: gitleaks, PRs and pushes to `main` plus weekly.
- Branch `development` does not exist; `main` has no protection; no environments, secrets or variables are configured.

### 5.2 Target design (matches B10 and your requirement)

Requirement: *a push to `main` deploys only what changed, after exactly the same build and test sequence as a PR.* The way to
make "same sequence" true by construction, not by convention, is **one definition of the sequence, called from both places**:

```
ci-backend.yml   (workflow_call + pull_request + push to development)   restore, build, unit + API integration tests, migrations check on Postgres,
                                                                          docker build (+ push of the SHA-tagged image only when called from deploy)
ci-android.yml   (workflow_call + pull_request + push to development)   build, lint, unit tests, instrumented tests, assembleRelease check
secret-scan.yml  (already runs on both)

deploy.yml       (push to main, path-filtered by job)
   changes  -> which of backend / android changed (dorny/paths-filter, or per-job `paths`)
   backend  -> uses: ci-backend.yml          # identical gates, builds the image once
            -> push image to ACR :<sha>      # the image that was tested is the image that ships; never rebuilt
            -> run EF migrations bundle against the target DB
            -> deploy to staging slot -> smoke test (/api/health, then a sync round trip, per B10.3) -> swap -> verify -> on failure, swap back
   android  -> uses: ci-android.yml
            -> assembleRelease signed (keystore from secret), versionCode = run number
            -> publish APK + SHA-256 to the distribution channel
```

Rules that avoid the usual traps:

1. `concurrency` with `cancel-in-progress: false` on deploy jobs. The current `cancel-in-progress: true` is right for CI but would kill a half-finished deploy or migration.
2. Run tests on the **merge commit** that is deployed; deploy by image digest, not by `latest`.
3. Path filters and required checks conflict: a required check that is skipped by a path filter blocks the merge forever. Either run the filter *inside* the workflow (jobs skipped, workflow always reports) or only require the always-running checks.
4. Authenticate to Azure with **OIDC federated credentials** (`azure/login@v2`, `id-token: write`), no client secret stored in GitHub. One federated credential per GitHub environment.
5. GitHub **environments** (`staging`, `production`) hold the variables (`ACR_NAME`, `WEBAPP_NAME`, `API_BASE_URL`) and the Android signing secrets. Required reviewers on `production` give a manual gate if wanted (see the caveat below).
6. Pin third-party actions to a commit SHA (the current workflows use floating tags; `reactivecircus/android-emulator-runner@v2` in particular).
7. Add `workflow_dispatch` inputs for "redeploy tag X" so rollback is one click (doc: "rollback is a re-swap or a redeploy of the previous ACR tag").
8. Keep `external-api-sim` out of the deploy path.

### 5.3 Repository settings (you do these; I can script them with `gh` once you confirm)

- Create `development` from `main`; add `development` to the `branches:` of the CI workflows (PR and push).
- Branch protection on `main` and `development`: PR required, required status checks, no direct pushes (document B10.1).
  **Caveat:** GitHub does not offer branch protection or environment reviewers on private repositories on the free plan
  (the secret-scan workflow already says GitHub's own secret scanning is unavailable on this repo, which points to the same
  plan). `gh api repos/.../branches/main/protection` currently returns "Branch not protected". If you cannot enable it,
  the guarantee that "only tested code deploys" rests on the workflow design above, so keep the deploy tests inside `deploy.yml` rather than relying on PR checks.
- Secrets/variables to create: none of the Azure ones need a password if OIDC is used; Android signing needs
  `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

> **Update (school tenant):** item 4 below (OIDC) could not be done: the school tenant blocks Microsoft Entra app registrations. The deploy uses the Web App's own webhook instead (one environment secret, `AZURE_WEBAPP_WEBHOOK_URL`); see `infra/README.md` section 4. The OIDC workflow is kept in `infra/alternatives/`.

## 6. Recommended order of work

1. **Decisions** (section 8), especially application id, database host, slots/tier, APIM/WAF scope, Entra vs own JWT.
2. **`development` branch + CI triggers + branch protection** (half a day; unblocks everything and is the document's flow).
3. **Blockers 1 and 3** (forwarding claim, remove legacy queue), plus migrations check on Postgres in CI.
4. **`infra/` Bicep** for the Test environment; first manual deploy of the image to a staging slot; fix `KnownNetworks`/`AllowedHosts`/TLS surprises while nothing depends on it.
5. **Deploy workflow** (`deploy.yml`) calling the CI workflows, with OIDC, migration bundle, smoke test, swap.
6. **Android release config** (items 1-5 and 13 in section 4) so a signed, minified APK is built by the same pipeline; test it on a device against the Test API.
7. **Blob storage**: API side, then Android upload worker, then #75 on 2G.
8. **Observability and alerts**, then #63 load/smoke test, then UAT (#60) in the UAT environment.
9. Security items needing product answers (#71, #73, app lock, signing self-check) scheduled **before the first APK reaches staff**.

## 7. Verified on 2026-10-03

- `./gradlew :app:assembleRelease -PapiBaseUrl=https://api.example.test/` (JDK 21, `main` at `ce7954d`) succeeds in 46 s
  and writes `app-release-unsigned.apk` (11.5 MB). It is **unsigned** (Android refuses to install it) and **not minified**
  (no R8 task ran; `isMinifyEnabled = false`). `lintVitalRelease` passes. So the build path works and the remaining
  Android release work is configuration (signing, R8 rules, id, versioning), not repair.
- `client/app/proguard-rules.pro` does not exist. It only goes unnoticed because minify is off.
- `gh api .../branches/main/protection` returns 404 "Branch not protected"; the repository has no Actions secrets,
  variables or environments.
- Not run: the Docker builds and `api.Tests` (CI already covers them, last recorded at 366 passing in `security-review.md`),
  and nothing was provisioned in Azure.

## 8. Decisions needed

| # | Question | Who | Default if you want me to proceed |
|---|---|---|---|
| 1 | Final `applicationId` | Team | none; must be chosen |
| 2 | Database: Azure Flexible Server vs SA Harvest-hosted | Team + SA Harvest | Flexible Server B1ms, add to the cost table |
| 3 | App Service tier: S1 (slots) vs B1 (no slots) for the pilot | Team | S1 for production only; Test/UAT on B1 without slots |
| 4 | APIM + WAF + private endpoints now, or later | Team | Later; record the risk acceptance |
| 5 | Entra ID tokens (document) vs own JWT (code) | Team | Keep own JWT; amend the document |
| 6 | Photo storage: our Blob account vs Foodspace's image storage; what Foodspace receives | SA Harvest / Foodspace | Own Blob account, private |
| 7 | APK distribution channel | Team + SA Harvest | Private Blob container with short-lived link + published SHA-256 |
| 8 | SQLCipher, app lock, pinning for v1? | Team + SA Harvest | Pinning (CA pin + backup) and app lock before rollout; SQLCipher decision recorded |
| 9 | Region | SA Harvest | Spain Central (decided) |
| 10 | Sentry vs Application Insights | Team | Application Insights (matches the document) |
