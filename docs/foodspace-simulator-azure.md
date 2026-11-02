# Running the Foodspace simulator on Azure

Foodspace's real API is not available to us, so on the live deployment the vetting list is empty and there is no Form 2 to open.
This guide puts the simulator (`external-api-sim/`) on Azure as its own small Web App and points the live API at it. Then the whole
flow works for testing: the vetting list fills with 3 fictional organisations, decisions are forwarded and can be inspected, and
Form 1 collections are forwarded too.

It is **test infrastructure with fictional data**. Switch it off (last section) and point `foodspaceBaseUrl` at the real Foodspace when
they give you access.

## What you end up with

```
Android app ──► sah-collection-vetting-api (existing)  ──https + X-Api-Key──►  sah-foodspace-sim (new)
                 caches /api/external/beneficiaries                              serves 3 fictional beneficiaries,
                 forwards decisions and collections                              records what it is sent (/received)
```

- One new Web App on the **same App Service plan** as the API (no extra monthly cost; your plan is the free F1).
- Its image: `ghcr.io/st10443409/saharvest-foodspace-sim:live`, built by the **Publish Foodspace simulator image** workflow.
- A shared API key stored in Key Vault as `Foodspace--ApiKey`, read by both Web Apps. The simulator refuses calls without it.
- The simulator keeps everything in memory: a restart resets what it has "received", and the 3 beneficiaries come back as new.

## Before you start

- Azure Cloud Shell (bash) signed in to the subscription that holds `sah-prod-rg`. The `az` CLI is not installed on your Mac.
- The GitHub **pull token** (classic, `read:packages`) you already created for the API. The simulator's image is a private package too.
- The workflow file `.github/workflows/publish-foodspace-sim.yml` must be on `main` (a workflow can only be started by hand from the
  default branch). Merge it the way you merge everything else: pull request into `development`, then into `main`. It only adds a
  workflow and docs, so the API is not redeployed.

Names used below (change them if you chose differently):

| Thing | Value |
|---|---|
| Resource group | `sah-prod-rg` |
| App Service plan | `sah-prod-plan` |
| Existing API Web App | `sah-collection-vetting-api` |
| New simulator Web App | `sah-foodspace-sim` (must be globally unique; if taken, pick another and use it everywhere) |
| GitHub user | `ST10443409` (image path must be lower-case: `st10443409`) |

## 1. Publish the simulator image

GitHub > your repository > **Actions** > **Publish Foodspace simulator image** > **Run workflow** (branch `main`) > wait for the green tick.

The first run creates the package `saharvest-foodspace-sim`. It stays private. Nothing else is needed: the workflow uses its own token.
Check it appeared under your profile > **Packages**.

## 2. Create the Web App (Cloud Shell)

```bash
RG=sah-prod-rg
PLAN=sah-prod-plan
SIM=sah-foodspace-sim
GH_USER=ST10443409
IMAGE=ghcr.io/st10443409/saharvest-foodspace-sim:live

read -rs -p "GitHub pull token: " TOKEN; echo
az webapp create -g $RG -p $PLAN -n $SIM \
  --container-image-name $IMAGE \
  --container-registry-url https://ghcr.io \
  --container-registry-user $GH_USER \
  --container-registry-password "$TOKEN" -o none
unset TOKEN

az webapp update -g $RG -n $SIM --https-only true -o none
az webapp config set -g $RG -n $SIM --min-tls-version 1.2 --ftps-state Disabled -o none
az webapp identity assign -g $RG -n $SIM -o none        # lets it read the key from Key Vault
```

If `az webapp create` complains about the `--container-*` arguments, your Cloud Shell has a different CLI version. Create it in the
portal instead: **Create a resource > Web App**, Publish **Container**, OS **Linux**, the same plan, Image source **Other container
registries**, Registry `https://ghcr.io`, the image above, then fill in the user and token under **Deployment Center**.

**The free F1 plan has no Always On** (the command above leaves it out, because Azure refuses it with `Conflict`). Both Web Apps sleep
after about 20 minutes without traffic, and the first request then takes 10 to 40 seconds. The API waits only 15 seconds for the
simulator, so a sync straight after a quiet spell can show an empty or stale list. Wake the simulator first, then sync:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://sah-foodspace-sim.azurewebsites.net/api/external/status   # 401 means it is awake
```

On B1 or above you can add `--always-on true` and this goes away (`appServicePlanSku` in `prod.bicepparam`, about US$13/month).

## 3. Create the shared API key

```bash
KV=$(az keyvault list -g $RG --query "[0].name" -o tsv); echo "$KV"
az keyvault secret set --vault-name $KV --name Foodspace--ApiKey --value "$(openssl rand -hex 32)" -o none
```

That generated value is never shown. Both Web Apps read it from the vault. (You may need the Key Vault Secrets Officer role you
already hold from `operatorObjectId`.)

## 4. Let the simulator read the key, and configure it

```bash
SIM_ID=$(az webapp identity show -g $RG -n $SIM --query principalId -o tsv)
KV_ID=$(az keyvault show -n $KV --query id -o tsv)
az role assignment create --assignee-object-id $SIM_ID --assignee-principal-type ServicePrincipal \
  --role "Key Vault Secrets User" --scope $KV_ID -o none

az webapp config appsettings set -g $RG -n $SIM -o none --settings \
  WEBSITES_PORT=8080 \
  WEBSITES_ENABLE_APP_SERVICE_STORAGE=false \
  ASPNETCORE_ENVIRONMENT=Production \
  "Simulator__ApiKey=@Microsoft.KeyVault(VaultName=$KV;SecretName=Foodspace--ApiKey)"

az webapp restart -g $RG -n $SIM
```

Role assignments take a few minutes to take effect. In the portal, **sah-foodspace-sim > Settings > Environment variables**: the
`Simulator__ApiKey` row must show a **green tick** ("Key Vault Reference"). If it shows a red cross, wait two minutes, restart, and
look again. If it is left as red, the simulator would treat the literal text `@Microsoft.KeyVault(...)` as the key.

## 5. Check the simulator by itself

```bash
KEY=$(az keyvault secret show --vault-name $KV --name Foodspace--ApiKey --query value -o tsv)
HOST=https://$SIM.azurewebsites.net

curl -s -o /dev/null -w "no key: %{http_code}\n" $HOST/api/external/status            # 401
curl -s -H "X-Api-Key: $KEY" $HOST/api/external/status                                  # {"status":"Online"}
curl -s -H "X-Api-Key: $KEY" $HOST/api/external/beneficiaries | grep -o '"id":"fs-[0-9]*"'   # fs-1001, fs-1002, fs-1003
```

The first request after a restart can take 30 to 60 seconds while the container starts. A `502` or `503` at first is normal; retry.
If it never comes up, **sah-foodspace-sim > Monitoring > Log stream** shows why (usually an image-pull error: the token or the image
path).

## 6. Point the API at it

The API's Foodspace settings are managed by `infra/environments/prod.bicepparam`, so they are changed there (a hand-edited setting
would be overwritten by the next deployment). The file in this repository already says:

```
param foodspaceBaseUrl = 'https://sah-foodspace-sim.azurewebsites.net/'
param foodspaceApiKeySecretExists = true
param foodspaceForwardingEnabled = true
```

If you chose a different simulator name in step 2, change the address to match. Merging that file changes nothing in Azure by itself:
the pipeline never applies `infra/`. It only takes effect when you run the deployment below.

`foodspaceApiKeySecretExists = true` is only safe now, because step 3 created the secret.

If you set the first-admin bootstrap values (`bootstrapAdminUsername` and friends) in this file or in the portal, it is time to clear
them: you have signed in, and the bootstrap has done its job (`infra/README.md`, section 7, step 4). Setting
`bootstrapAdminUsername = ''` and `bootstrapAdminPasswordSecretExists = false` removes both settings on this deployment. Then
delete the spent secret: `az keyvault secret delete --vault-name $KV --name Bootstrap--AdminPassword`.

Apply it from Cloud Shell (clone the repo, or upload the edited file):

```bash
git clone https://github.com/ST10443409/INSY7315-Stein-Solutions---SA-Harvest-Beneficiary-Direct-Collection-Mobile-Application.git
cd INSY7315-Stein-Solutions---SA-Harvest-Beneficiary-Direct-Collection-Mobile-Application
# (the Foodspace values are already in infra/environments/prod.bicepparam; change the address there if your simulator has another name)
az deployment group what-if --resource-group $RG --parameters infra/environments/prod.bicepparam   # read it: only app settings change
az deployment group create  --resource-group $RG --parameters infra/environments/prod.bicepparam --name main
```

The API restarts and now uses the simulator. This does not touch the simulator Web App: it is not part of the template.
Also commit the edited `prod.bicepparam` so the repository matches what is deployed.

## 7. Prove it end to end

1. **Vetting list.** Sign in to the app as the vetting officer and sync, or ask the API directly (the first call fills the 15-minute cache):

   ```bash
   API=https://sah-collection-vetting-api.azurewebsites.net
   read -rs -p "Vetting password: " P; echo
   T=$(curl -s -X POST $API/api/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"<vetting user>\",\"password\":\"$P\"}" | grep -o '"token":"[^"]*"' | cut -d'"' -f4); unset P
   curl -s -H "Authorization: Bearer $T" "$API/api/vetting/records" | grep -o '"legalName":"[^"]*"'
   ```

   You should see three organisations. In the app the list now shows them, and tapping one opens Form 2.
2. **Decision.** Record an approve/reject/flag on a record, let the app sync (it is queued while offline), then:

   ```bash
   curl -s -H "X-Api-Key: $KEY" $HOST/api/external/received
   ```

   The decision appears under `vettingDecisions` within about 15 seconds (the API's forwarding loop interval).
3. **Collection (Form 1).** Create the collector with CBO id `cbo-001` (one of the simulator's CBOs; the simulator only requires
   that it is not empty), submit a collection, and look for it under `cboCollections` in the same `/received` output.

## Using it

- **Reset the data:** `az webapp restart -g $RG -n $SIM`. The received decisions and collections are forgotten.
- **Show a Foodspace outage in a demo:** `curl -X POST $HOST/api/external/simulate/outage` (add `?status=500` for another error), then
  `.../simulate/recover`. The API keeps serving its cached list, marked stale, and records wait to be forwarded. These two control
  endpoints are not protected by the key (they live outside the key check in the simulator), so anyone who knows the address can
  start an outage. The data is fictional; just know it.
- **Update the simulator after a code change:** run **Publish Foodspace simulator image** again, then `az webapp restart -g $RG -n $SIM`.
- **The cache:** the API refreshes the beneficiary list at most every 15 minutes (`Foodspace__BeneficiaryCacheMinutes`).

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| App still says "No records on this device yet" | The API's list is empty or the app has not synced. Run the `curl` in step 7.1. If it returns an error, the API cannot reach the simulator: check steps 5 and 6. Then pull to refresh in the app. |
| `/api/vetting/records` says Foodspace is unavailable | The API cannot get the list. Wrong `foodspaceBaseUrl` (must be `https://`, no typos), the key differs between the two apps, or the simulator is asleep/starting. Check the API's Log stream for `Beneficiary refresh failed`. |
| Simulator answers `401` to the API | The two apps hold different keys, or one still has the literal `@Microsoft.KeyVault(...)` text (red cross). Fix the vault reference, restart both. |
| Image will not pull (log stream) | The token expired or lacks `read:packages`, or the image path is wrong (`st10443409` must be lower-case). Update the registry settings under **Deployment Center**. |
| API will not start after step 6 | `foodspaceBaseUrl` is not https, or it was set without the secret. Read the Log stream: the startup check names every problem. |
| `502`/`503` for the first minute | Normal while the container starts. |

## Switching to the real Foodspace later

Set `foodspaceBaseUrl` to their https address in `prod.bicepparam`, put their key in the `Foodspace--ApiKey` secret, redo step 6's
deployment, and check `docs/OPEN-DECISIONS.md` (their authentication and paging may differ). Then delete the simulator Web App:
`az webapp delete -g $RG -n $SIM`. The 3 fictional organisations disappear from the list at the next cache refresh.
