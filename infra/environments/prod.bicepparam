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
param foodspaceBaseUrl = ''
param foodspaceApiKeySecretExists = false
param foodspaceForwardingEnabled = false

param migrateOnStartup = true

// az ad signed-in-user show --query id -o tsv
param operatorObjectId = ''
