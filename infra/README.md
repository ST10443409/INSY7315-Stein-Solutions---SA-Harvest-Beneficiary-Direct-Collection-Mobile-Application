# Azure setup and deployment (#58)



The API runs as a container on an Azure App Service. This folder creates everything it needs; `.github/workflows/deploy.yml`
puts each release on it. The Android app is **not** deployed to Azure: it is built into an APK (see `docs/android-release.md`).

```
push to main ─► CI gates (build, tests incl. PostgreSQL migrations, lint, secret scan) ─► image to GHCR ─► Azure Web App ─► smoke test ─► done
                                                                                                                       └─ fails ─► previous image restored
```

## What gets created (`infra/main.bicep`)

| Resource | Why | Notes |
|---|---|---|
| App Service plan (Linux) + Web App for Containers | runs the API | system-assigned identity; HTTPS only, TLS 1.2+, FTP off; **no slots** on this tier |
| Key Vault | the JWT signing key, database connection string, registry pull token, later the Foodspace key | RBAC; the app reads secrets with its identity through Key Vault references, so no secret is in the image or the settings |
| Storage account + private `attachments` container | photos and signatures (the API uploads them with its identity; Admins read them back through the API) | no public access, **no account keys** (identity only), soft delete, GRS in prod |
| Log Analytics + Application Insights | monitoring | connection string is set; the API does not send telemetry yet (#57) |

Not created, on purpose: the **database** (SA Harvest's), the **container registry** (GitHub's, free), the **secret values**.

## One-time setup, in order

You need: the Azure CLI (`az`), the GitHub CLI (`gh`), and an Azure subscription. Run these from the repository root in Git Bash.

### 1. Pick the names and check the region

Edit `infra/environments/prod.bicepparam`: `webAppName` (becomes `https://<name>.azurewebsites.net`, **baked into every APK, so choose
once**) and `appServicePlanSku`. `F1` is free but has no Always On (the process sleeps when idle, which stops the Foodspace retry
loop) and may not be able to run a custom container: check in the portal before relying on it. `B1` is about US$13/month.

```bash
az login
az account set --subscription "<name or id>"
az account list-locations --query "[?name=='spaincentral'].displayName" -o tsv      # must print "Spain Central"
az appservice list-locations --sku B1 --linux-workers-enabled --query "[?name=='Spain Central']" -o tsv   # must print a line
az provider register --namespace Microsoft.Web  # and Microsoft.KeyVault, Microsoft.Storage, Microsoft.Insights, Microsoft.OperationalInsights, if not already registered
```

A student subscription can restrict regions: if `Spain Central` is refused, pick one that is allowed and say so in
`docs/azure-deployment-readiness.md` (keep every resource in the same region).

### 2. Create the infrastructure

```bash
RG=sah-prod-rg
az group create --name $RG --location spaincentral
az ad signed-in-user show --query id -o tsv      # paste into operatorObjectId in prod.bicepparam (lets you set secrets in step 3)
az deployment group what-if --resource-group $RG --parameters infra/environments/prod.bicepparam     # read it
az deployment group create  --resource-group $RG --parameters infra/environments/prod.bicepparam --name main
```

It is safe to run again: Bicep only changes what differs. (The first run needs `Owner` or `User Access Administrator` on the group,
because it grants the Web App's identity access to the vault and the storage container.)

### 3. Set the secrets (never in a file, never in chat)

`read -s` keeps them out of the screen and your shell history. The vault name is an output of step 2.

```bash
KV=$(az deployment group show -g $RG -n main --query properties.outputs.keyVaultName.value -o tsv)

az keyvault secret set --vault-name $KV --name Jwt--SigningKey --value "$(openssl rand -base64 48)" -o none

read -rs -p "Database connection string: " V; echo
az keyvault secret set --vault-name $KV --name ConnectionStrings--Default --value "$V" -o none; unset V
# Must end with SSL Mode=VerifyFull (or Require): the API refuses to start with an unencrypted database connection.

read -rs -p "GitHub token (classic, read:packages) the Web App pulls the image with: " V; echo
az keyvault secret set --vault-name $KV --name Registry--PullToken --value "$V" -o none; unset V

# The first administrator's password (see "The first administrator" below). 12+ characters, not containing the username.
read -rs -p "First admin password: " V; echo
az keyvault secret set --vault-name $KV --name Bootstrap--AdminPassword --value "$V" -o none; unset V
```

Create that token at GitHub > Settings > Developer settings > Personal access tokens (**classic**, scope `read:packages` only, set an
expiry and put the date in your calendar: when it lapses the Web App cannot pull a new image, and the deploy fails with an
image-pull error). The database connection string comes from SA Harvest (see "Not in place yet").

Later, when Foodspace gives its key: `az keyvault secret set --vault-name $KV --name Foodspace--ApiKey ...`, then set
`foodspaceApiKeySecretExists = true`, `foodspaceBaseUrl` and (when ready) `foodspaceForwardingEnabled = true` in the parameter file and
redo step 2.

### 4. Let GitHub deploy (the Web App's own webhook; no Azure login)

The pipeline never logs in to Azure. The Web App runs the moving tag `ghcr.io/<owner>/saharvest-api:live`; a deployment repoints `live`
at the new image and POSTs the Web App's **webhook URL**, which makes App Service restart and pull it (Microsoft's documented way to deploy
from a registry that is not ACR or Docker Hub). That URL carries the Web App's deployment credentials, so it is stored as one **environment
secret** that only `main` can read. This works when the school/tenant blocks Microsoft Entra app registrations; if your tenant allows them,
the passwordless OIDC variant is kept in `infra/alternatives/deploy-backend-oidc.yml.txt`.

1. Allow the credentials: Web App > **Settings > Configuration > General settings** > **SCM Basic Auth Publishing Credentials** = **On** > Save.
   (Without it the webhook answers 401 and the deploy step fails with that.)
2. Point the Web App at the `live` tag **once**, after the first pipeline run has published it (step 6): Web App > **Deployment > Deployment
   Center** > Registry source *Private registry*, server `https://ghcr.io`, login your GitHub user, password the pull token, image
   `<owner lower-case>/saharvest-api`, tag **`live`**; **Continuous deployment: On** (that is what creates the webhook and makes a POST to it restart the app and pull the image;
   GHCR has no registry-side webhook, so nothing but our pipeline ever calls it); Save. (Via the CLI: `az webapp config container set -g $RG -n $APP
   --container-image-name ghcr.io/<owner>/saharvest-api:live`, then `az webapp deployment container config -g $RG -n $APP --enable-cd true --query CI_CD_URL -o tsv`
   prints the webhook URL; the registry settings from step 2 are already in place.)
3. Copy the webhook URL: Deployment Center > **Webhook URL** > Copy (it looks like `https://$<app>:<password>@<app>.scm.<region>.azurewebsites.net/api/registry/webhook`).
   Treat it like a password.

### 5. Tell GitHub where to deploy

```bash
REPO=ST10443409/INSY7315-Stein-Solutions---SA-Harvest-Beneficiary-Direct-Collection-Mobile-Application
gh api -X PUT repos/$REPO/environments/production --input - <<'JSON'
{ "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true } }
JSON
gh api -X POST repos/$REPO/environments/production/deployment-branch-policies -f name=main     # only main may use it

gh variable set API_BASE_URL --env production --repo $REPO --body "https://<the Web App's default domain>/"      # with the trailing slash
read -rs -p "Webhook URL: " V; echo; gh secret set AZURE_WEBAPP_WEBHOOK_URL --env production --repo $REPO --body "$V"; unset V
```

(Or in the browser: repository **Settings > Environments > production**: deployment branch `main` only, variable `API_BASE_URL`, secret `AZURE_WEBAPP_WEBHOOK_URL`.)
Restricting the environment to `main` means a pull-request branch cannot read the webhook secret. The Android signing secrets
(`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) go in the same environment once the
keystore exists (`docs/android-release.md`); until then the release job skips with a warning. Optional: `SMOKE_TEST_USERNAME` /
`SMOKE_TEST_PASSWORD` secrets let the smoke test also sign in.

### 6. First deployment

Merge `development` into `main` (a pull request). That push runs `deploy.yml`. **The first run is the shakedown and is expected to fail its
smoke test**: the Web App still runs the placeholder image until step 4.2 points it at `live`, and `live` only exists once the first run has
published it. Then: do step 4.2, and run **Actions > Redeploy backend** with the 12-character revision from the first run (it is the image tag).
From then on every push to `main` deploys by itself. If a deployment fails its checks, `live` is pointed back at the last good image
(`last-good`) and the webhook is called again; the very first deployment has no last good image, so it is only reported.

### 7. The first administrator

A fresh deployment has nobody who can sign in, and only an Admin can create accounts, so the very first Admin comes from
configuration. It is created once when the API starts, and does nothing if an active Admin already exists. Full account
management (creating collectors and officers, deactivating a lost phone's owner, resetting a password) is in
[docs/user-accounts.md](../docs/user-accounts.md).

1. The `Bootstrap--AdminPassword` secret was set in step 3. In `infra/environments/prod.bicepparam` set `bootstrapAdminUsername`
   (3 to 64 characters: lower-case letters, digits, `.`, `_`, `-`) and `bootstrapAdminPasswordSecretExists = true`. Set **both**: the API
   refuses to start with only one, on purpose, so a typo cannot quietly leave you with no admin.
2. Redo step 2 (`az deployment group create ...`). The Web App restarts and creates the account; the log says so
   (`Created the first administrator ...`) and never prints the password.
3. Sign in once to prove it (replace the host; the password is read without being echoed):
   ```bash
   read -rs -p "Admin password: " P; echo
   curl -s -X POST https://<your-app>.azurewebsites.net/api/auth/login -H 'Content-Type: application/json' \
     -d "{\"username\":\"<the username>\",\"password\":\"$P\"}" | grep -o '"role":"[A-Z_]*"'; unset P     # "role":"ADMIN"
   ```
4. **Then remove the bootstrap**: set `bootstrapAdminPasswordSecretExists = false` and `bootstrapAdminUsername = ''`, redo step 2, and
   `az keyvault secret delete --vault-name $KV --name Bootstrap--AdminPassword`. It has done its job; a secret that is no longer needed
   should not stay around. (If you forget, nothing breaks: it does nothing while an active Admin exists.)
5. Put the smoke test's account in too: create a low-privilege account for it (a Vetting officer called e.g. `smoke.test`) as in
   `docs/user-accounts.md`, and store its username and password as the `SMOKE_TEST_USERNAME` / `SMOKE_TEST_PASSWORD` secrets of the
   GitHub `production` environment, so the deploy's smoke test also proves a real sign-in.

## Day to day

| I want to | Do this |
|---|---|
| ship a change | open a PR into `development`, then `development` into `main`; the push to `main` deploys what changed |
| see what is deployed | `az webapp config show -g $RG -n $APP --query linuxFxVersion -o tsv` (the tag is the first 12 characters of the commit) |
| roll back | Actions > **Redeploy backend** > the tag of the last good build (tags are under the repository's Packages) |
| read the API's logs | `az webapp log config -g $RG -n $APP --docker-container-logging filesystem` then `az webapp log tail -g $RG -n $APP` |
| remove everything | `az group delete -n $RG` (a production Key Vault has purge protection and keeps its name for 90 days) |

## When the first deployment fails: what it means

| Symptom | Cause |
|---|---|
| smoke test: health never answers; log shows `Jwt:SigningKey is not configured` or a literal `@Microsoft.KeyVault(` | the secret is missing in the vault, or the Web App's identity cannot read it (the app setting shows a red Key Vault reference in the portal) |
| `Refusing to start with an unsafe configuration` | read the listed reasons: database not on TLS (`SSL Mode`), Foodspace address not https, or test accounts enabled |
| `403 HTTPS_REQUIRED` from `/api/auth/me` | `Security__KnownNetworks` is not reaching the app, so forwarded HTTPS is not believed |
| `400` from every call | the host name is not in `AllowedHosts` (add a custom domain to `extraAllowedHosts`) |
| 503 from `/api/health`, `database: unavailable` | the database is unreachable: SA Harvest's firewall must admit the Web App's outbound addresses (`az webapp show -g $RG -n $APP --query outboundIpAddresses`) |
| image pull error in the deployment log | the registry token expired or lacks `read:packages`, or the package is not visible to that user |

## Not in place yet

- **SA Harvest's database.** The API cannot start without it. It needs: a reachable host and port, TLS, a database role that may create
  and alter tables (EF migrations create the API's own tables: users, sync state, admin actions), and SA Harvest's firewall admitting the
  Web App's outbound addresses (shared and can change on a free/basic plan; a fixed address needs VNet integration and NAT, which cost money).
- **An admin screen for accounts in the Android app.** Accounts are managed through the API for now (`docs/user-accounts.md`); the
  endpoints exist and are tested, the app does not call them yet.
- **Foodspace** base address and authentication (`docs/OPEN-DECISIONS.md` #1), so forwarding stays off.
- **Telemetry to Application Insights**: the resource and setting exist, the code does not.
- **Signatures and photos** are built (the API stores them in the `attachments` container using `Storage__AccountUri` and its managed
  identity's **Storage Blob Data Contributor** role on that one container; there is no key anywhere). To check it on a live deployment:
  sign in as a collector, send a collection and a file (see `docs/user-accounts.md` for tokens; the upload is
  `PUT /api/cbo-collection/<collection>/attachments/<id>?kind=PHOTO&slot=0` with the image as the body), then as an Admin
  `GET /api/admin/collections/<collection>/attachments` and `GET /api/admin/attachments/<id>`. A 503 on upload means the API cannot reach
  the container: check the role assignment (it can take a few minutes to apply) and `Storage__AccountUri`. What Foodspace receives is
  still open (`docs/OPEN-DECISIONS.md`).
- **Release keystore** and the signed-APK secrets; **certificate pinning**, which needs the final host name.
