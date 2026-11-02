using '../main.bicep'

// The first (and, on the free tier, only) environment: what a push to main deploys to. Until SA Harvest's database and
// Foodspace are connected it is a pre-production instance; the name is "prod" so the protections that cannot be undone
// (Key Vault purge protection, geo-redundant storage) are on from the start. For a throwaway environment copy this file as
// test.bicepparam and set environmentName = 'test'.

param environmentName = 'prod'
param namePrefix = 'sah'

// CHOOSE ONCE: this becomes https://<webAppName>.azurewebsites.net, which is baked into every APK. Must be globally unique.
param webAppName = 'sah-collection-vetting-api'

// F1 is free but sleeps when idle (no Always On), which stops the Foodspace retry loop. B1 (about US$13/month) is the floor
// for real use. Change here and redeploy; no data is lost.
param appServicePlanSku = 'B1'

// The GitHub user the container-registry pull token (a classic personal access token with read:packages) belongs to.
param registryUsername = 'ST10443409'

// Foodspace. Until they give us access, the live API talks to the Foodspace SIMULATOR, which runs as its own Web App on this plan
// and serves fictional beneficiaries (docs/foodspace-simulator-azure.md). To use the real Foodspace instead, put their https address
// here and their key in the Foodspace--ApiKey secret (docs/OPEN-DECISIONS.md #1).
// Before applying this: create the simulator Web App and the Foodspace--ApiKey secret (steps 1-4 of that guide). A true
// foodspaceApiKeySecretExists with no such secret hands the API the literal text of the reference.
param foodspaceBaseUrl = 'https://sah-foodspace-sim.azurewebsites.net/'
param foodspaceApiKeySecretExists = true
param foodspaceForwardingEnabled = true

// The FIRST administrator (docs/user-accounts.md). Set both, redo step 2 of infra/README.md, sign in, then clear both and redo
// step 2 again and delete the secret: the bootstrap does nothing once an admin exists, and a spent secret should not linger.
param bootstrapAdminUsername = ''
param bootstrapAdminPasswordSecretExists = false

param migrateOnStartup = true

// az ad signed-in-user show --query id -o tsv
param operatorObjectId = ''
