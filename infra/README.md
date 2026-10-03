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
| Storage account + private `attachments` container | photos and signatures (the API upload endpoint is not built yet) | no public access, **no account keys** (identity only), soft delete, GRS in prod |
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
```

Create that token at GitHub > Settings > Developer settings > Personal access tokens (**classic**, scope `read:packages` only, set an
expiry and put the date in your calendar: when it lapses the Web App cannot pull a new image, and the deploy fails with an
image-pull error). The database connection string comes from SA Harvest (see "Not in place yet").

Later, when Foodspace gives its key: `az keyvault secret set --vault-name $KV --name Foodspace--ApiKey ...`, then set
`foodspaceApiKeySecretExists = true`, `foodspaceBaseUrl` and (when ready) `foodspaceForwardingEnabled = true` in the parameter file and
redo step 2.

### 4. Let GitHub deploy without a password (OIDC)

GitHub proves to Azure who a workflow run is with a short-lived token; no Azure secret is stored in GitHub.

```bash
REPO=ST10443409/INSY7315-Stein-Solutions---SA-Harvest-Beneficiary-Direct-Collection-Mobile-Application
APP=$(az deployment group show -g $RG -n main --query properties.outputs.webAppName.value -o tsv)

APPID=$(az ad app create --display-name sah-github-deploy --query appId -o tsv)
az ad sp create --id $APPID -o none
az ad app federated-credential create --id $APPID --parameters "{
  \"name\": \"github-production\",
  \"issuer\": \"https://token.actions.githubusercontent.com\",
  \"subject\": \"repo:$REPO:environment:production\",
  \"audiences\": [\"api://AzureADTokenExchange\"] }" -o none
# Least privilege: change this one Web App, nothing else.
az role assignment create --assignee $APPID --role "Website Contributor" \
  --scope "$(az webapp show -g $RG -n $APP --query id -o tsv)" -o none
```

### 5. Tell GitHub where to deploy

```bash
gh api -X PUT repos/$REPO/environments/production --input - <<'JSON'
{ "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true } }
JSON
gh api -X POST repos/$REPO/environments/production/deployment-branch-policies -f name=main     # only main may use it

gh variable set AZURE_CLIENT_ID       --env production --repo $REPO --body "$APPID"
gh variable set AZURE_TENANT_ID       --env production --repo $REPO --body "$(az account show --query tenantId -o tsv)"
gh variable set AZURE_SUBSCRIPTION_ID --env production --repo $REPO --body "$(az account show --query id -o tsv)"
gh variable set AZURE_RESOURCE_GROUP  --env production --repo $REPO --body "$RG"
gh variable set AZURE_WEBAPP_NAME     --env production --repo $REPO --body "$APP"
gh variable set API_BASE_URL          --env production --repo $REPO --body "$(az deployment group show -g $RG -n main --query properties.outputs.apiBaseUrl.value -o tsv)"
```

Restricting the environment to `main` means a pull-request branch cannot use the Azure credential, even though the OIDC trust
names the environment. The Android signing secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`) go in the same environment as secrets once the keystore exists (`docs/android-release.md`); until then the
release job skips with a warning. Optional: `SMOKE_TEST_USERNAME` / `SMOKE_TEST_PASSWORD` secrets let the smoke test also sign in.

### 6. First deployment

Merge `development` into `main` (a pull request). That push runs `deploy.yml`. The first run is expected to be the shakedown: if a
secret is missing or the database cannot be reached, the smoke test fails and the previous (placeholder) image is restored.
Read the failing step, fix the cause, and run **Actions > Redeploy backend** with the same tag (no new commit needed).

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
- **User accounts.** The only code that creates login accounts is the Development-only seeder, which the API refuses to run anywhere
  else, so a deployed API has no users. See `docs/azure-deployment-readiness.md`.
- **Foodspace** base address and authentication (`docs/OPEN-DECISIONS.md` #1), so forwarding stays off.
- **Photo upload to the storage account**, and **telemetry to Application Insights**: the resources and settings exist, the code does not.
- **Release keystore** and the signed-APK secrets; **certificate pinning**, which needs the final host name.
