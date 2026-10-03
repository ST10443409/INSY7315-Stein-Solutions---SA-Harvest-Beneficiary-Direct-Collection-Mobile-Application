# Android release build (#59)

How the release APK is built, signed and checked. The APK is distributed to SA Harvest staff directly (no app store; design
document B6.5 and B11), so **you** are the update channel: an APK that cannot install over the previous one, or that crashes on
a phone, cannot be fixed by the store. Read the keystore section before building anything you will give to anyone.

## What identifies the app

| | Value | Where it comes from |
|---|---|---|
| Application id | `za.org.saharvest.collectionvetting` | `applicationId` in `client/app/build.gradle.kts`. **Never change it after the first APK is installed:** Android treats a new id as a different app, and the old app's unsynced records stay behind on the phone. |
| Display name | `SAH Collection & Vetting` | `app_name` in `res/values/strings.xml` |
| Code package | `com.example.client` | `namespace`. Unrelated to the id above and deliberately unchanged (renaming it would touch every file for no user-visible gain). |
| Version | `versionCode` / `versionName` | `-PappVersionCode=<n> -PappVersionName=<x.y.z>`. **`versionCode` must go up with every APK that reaches a phone**; Android refuses to install a lower one. The release pipeline passes the run number. Local builds default to `1` / `1.0` and must never be distributed. |
| API address | `-PapiBaseUrl=https://<host>/` | Baked in at build time; the release build refuses anything but `https://`. A custom domain in front of the API means a later host change does not need a new APK. |

Build variants: `debug` (local, plain HTTP to the emulator's host), `release` (shipped), `r8Check` (see below; never shipped).

## The release keystore (do this once, carefully)

The keystore is the identity every future update must be signed with. **If it is lost, no phone can be updated: staff must
uninstall (losing unsynced records) and reinstall.** If it leaks, anyone can publish an APK that installs as a genuine update.
It and its passwords are never in the repository (`.gitignore` refuses `*.jks`, `*.keystore` and `keystore.properties`; gitleaks
does not look inside a binary keystore, so this is on you).

The person who owns releases creates it on their own machine. `keytool` prompts for the passwords, so they never land in shell
history or in a chat:

```bash
keytool -genkeypair -v -keystore sah-release.jks -alias sah-release -keyalg RSA -keysize 4096 -validity 10000
```

Then:

1. **Back it up in at least two separate places** that are not the repository (an encrypted password manager attachment and an
   offline copy), with the two passwords and the alias `sah-release` recorded next to it. Test that the backup opens
   (`keytool -list -keystore <copy>`).
2. Record the certificate fingerprint, which the team compares against every published APK:
   `keytool -list -v -keystore sah-release.jks -alias sah-release` (the `SHA256:` line).
3. Store it for CI as repository **secrets** (Settings > Secrets and variables > Actions), never as variables:

   | Secret | Value |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | the keystore file, base64: PowerShell `[Convert]::ToBase64String([IO.File]::ReadAllBytes("sah-release.jks")) \| Set-Clipboard`, or bash `base64 -w0 sah-release.jks` |
   | `ANDROID_KEYSTORE_PASSWORD` | the store password |
   | `ANDROID_KEY_ALIAS` | `sah-release` |
   | `ANDROID_KEY_PASSWORD` | the key password |

   The release pipeline (set up with the deploy workflow) decodes the first secret to a temporary file and exports
   `ANDROID_KEYSTORE_FILE` pointing at it; the build reads the four values from the environment.

## Building

| Goal | Command (from `client/`) | Result |
|---|---|---|
| The pull-request check | `./gradlew assembleRelease -PapiBaseUrl=https://api.example.test/` | R8 on, lint-vital run, **unsigned** (Android will not install it). CI does this on every Android pull request. |
| A signed release (CI) | the same, plus `-PrequireReleaseSigning=true -PappVersionCode=$RUN -PappVersionName=$NAME` and the four `ANDROID_*` variables | `app/build/outputs/apk/release/app-release.apk`. With `requireReleaseSigning`, a missing keystore **fails the build** instead of producing an unsigned APK. |
| A signed release on your own machine | copy `keystore.properties.example` to `keystore.properties`, fill it in, then as above | the same |

Verify any APK before giving it to anyone (build-tools from the Android SDK):

```bash
apksigner verify --verbose --print-certs app-release.apk     # "Verifies", and the SHA-256 must be the recorded fingerprint
aapt2 dump badging app-release.apk | head -1                   # package, versionCode, versionName
```

Publish the APK's own SHA-256 (`sha256sum app-release.apk`) wherever the APK is published, so staff and supervisors can check
what they downloaded (risk register: sideloading a tampered APK).

**Keep the R8 mapping file of every release** (`app/build/outputs/mapping/release/mapping.txt`, per `versionCode`). Without it a
crash report from a phone is unreadable (`a.b.c`); with it, `retrace mapping.txt trace.txt` restores the names. The release
pipeline archives it next to the APK.

## R8 (shrinking and obfuscation) and how to check it

Release builds are minified (`isMinifyEnabled`, `isShrinkResources`), which the design document requires (B9.4). R8's failure mode is
**silent**: the build succeeds and the app then misbehaves on a phone. The one real danger here is Gson, which maps JSON to our
classes by *field name*; if R8 renames the fields, requests go out with the wrong keys and responses arrive empty, with no crash
and no error. `client/app/proguard-rules.pro` keeps exactly what is reached by name (the `network` package, the Room entities
used as request/response bodies, enums) and the Retrofit R8-full-mode rules; each rule says what breaks without it.

**If you add a request/response class outside `com.example.client.network` or `data.local.entity`, add a keep rule for its package
(or annotate it `@Keep`).** A unit test cannot catch this, because unit tests run unminified.

**Checking a minified build against the real backend.** The real release variant refuses plain HTTP, so it cannot be tried
locally; `r8Check` is the same build (same rules) made debuggable and allowed to reach the emulator's host, installed beside the
real app as `za.org.saharvest.collectionvetting.r8check` and signed with the debug key so it can never replace a release.

```bash
docker compose up -d                                           # local API, database and Foodspace simulator
cd client && ./gradlew installR8Check -PapiBaseUrl=http://10.0.2.2:5000/
```

Then, on the emulator, sign in as the seeded vetting user (the password is `SEED_TEST_PASSWORD` in `.env`) and check that: the
beneficiary records list shows names, provinces and contacts (not blank rows); a decision saved on one of them reaches the server
(`docker compose exec db psql -U saharvest -d saharvest -c "select outcome, officer_id from vetting_decisions order by received_at desc limit 1"`);
and `adb logcat` shows no `FATAL EXCEPTION`, `ClassCastException`, `JsonSyntaxException` or `NoSuchFieldError`. Repeat as the
collector and the admin when you change something they use. Do this whenever the proguard rules, a network class, or a library
version changes.

Last verified 2026-10-03 (vetting sign-in, record download of 3 beneficiaries, one Approve decision saved and forwarded, no crash).
The collector form upload and the admin screens use the same classes under the same rule but were not driven by hand.

## Not done yet (decisions or work still open)

These are in `docs/azure-deployment-readiness.md` section 4 and must be settled **before the first APK reaches staff**, because
several cannot be added to an installed app without an update:

- **Certificate pinning (#73)**: needs the production host decided; pin the issuing CA with a backup pin, never a leaf.
- **Local database encryption (#71)** (the design document says SQLCipher) and an **app PIN/biometric lock** (B9.4).
- **Signing-certificate self-check at launch** (risk register: tampered APK).
- **Distribution (#62)**: where staff download the APK (private Blob container with a short-lived link is the default proposal).
- **Signed release in the pipeline**: added with the deploy workflow (it needs the four secrets above).
