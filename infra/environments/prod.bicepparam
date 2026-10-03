using '../main.bicep'

// The first (and, on the free tier, only) environment: what a push to main deploys to. Until SA Harvest's database and
// Foodspace are connected it is a pre-production instance; the name is "prod" so the protections that cannot be undone
// (Key Vault purge protection, geo-redundant storage) are on from the start. For a throwaway environment copy this file as
// test.bicepparam and set environmentName = 'test'.

param environmentName = 'prod'
param namePrefix = 'sah'

// CHOOSE ONCE: this becomes https://<webAppName>.azurewebsites.net, which is baked into every APK. Must be globally unique.
param webAppName = 'REPLACE-WITH-YOUR-WEBAPP-NAME'

// F1 is free but sleeps when idle (no Always On), which stops the Foodspace retry loop. B1 (about US$13/month) is the floor
// for real use. Change here and redeploy; no data is lost.
param appServicePlanSku = 'B1'

// The GitHub user the container-registry pull token (a classic personal access token with read:packages) belongs to.
param registryUsername = 'ST10443409'

// Fill in when Foodspace gives them (docs/OPEN-DECISIONS.md #1); keep forwarding off until then.
// NOT empty, even though forwarding is off: with no address the API falls back to http://localhost:5284 and its own safety check
// refuses to start on a non-HTTPS Foodspace address. This placeholder is never called while forwarding is off; the vetting
// records list simply reports Foodspace as unavailable until the real address replaces it. ".invalid" cannot resolve, on purpose.
param foodspaceBaseUrl = 'https://foodspace-not-configured.invalid/'
param foodspaceApiKeySecretExists = false
param foodspaceForwardingEnabled = false

// The FIRST administrator (docs/user-accounts.md). Set both, redo step 2 of infra/README.md, sign in, then clear both and redo
// step 2 again and delete the secret: the bootstrap does nothing once an admin exists, and a spent secret should not linger.
param bootstrapAdminUsername = ''
param bootstrapAdminPasswordSecretExists = false

param migrateOnStartup = true

// az ad signed-in-user show --query id -o tsv
param operatorObjectId = ''
