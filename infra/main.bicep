// SA Harvest backend: everything the containerised API needs in Azure, as code, so Test, UAT and Production are provably the
// same shape (design document B6.5). One resource group per environment; apply with infra/README.md.
//
// What this creates:  App Service plan + Web App for Containers (system-assigned identity), Key Vault (RBAC), a storage account
// with one PRIVATE blob container for photos and signatures, Log Analytics + Application Insights.
// What it does NOT create: the database (SA Harvest hosts it), the container registry (GitHub Container Registry, free), and
// the secrets themselves. Secrets are set with `az keyvault secret set` (infra/README.md) so their values never pass through
// this file, its parameters or the deployment history.
targetScope = 'resourceGroup'

@description('Which environment this is. Production turns on protections that cannot be undone (Key Vault purge protection).')
@allowed(['test', 'uat', 'prod'])
param environmentName string

@description('Short lowercase prefix for names that need not be globally pretty (Key Vault, storage), e.g. "sah".')
@minLength(2)
@maxLength(8)
param namePrefix string = 'sah'

@description('The Web App name. GLOBALLY unique, and it becomes the API host name (<name>.azurewebsites.net) that is baked into every APK: choose once.')
@minLength(2)
@maxLength(60)
param webAppName string

@description('App Service plan SKU. F1 is free but has no Always On (the process sleeps when idle, which stops the Foodspace retry loop) and a daily CPU quota; B1 is the realistic floor. Slots need S1 or above.')
@allowed(['F1', 'B1', 'B2', 'S1', 'P1v3'])
param appServicePlanSku string = 'B1'

@description('The image the Web App starts with. The deploy pipeline replaces it on every release; this one only has to exist so the first infrastructure deployment succeeds.')
param initialContainerImage string = 'mcr.microsoft.com/dotnet/samples:aspnetapp'

@description('Registry that holds the API image. GitHub Container Registry is free for private images.')
param registryServer string = 'ghcr.io'

@description('GitHub user the pull token belongs to.')
param registryUsername string

@description('Public host names the API answers to (AllowedHosts), besides the default <webAppName>.azurewebsites.net. Add a custom domain here.')
param extraAllowedHosts array = []

@description('Foodspace API base address, https only. Empty until Foodspace gives one.')
param foodspaceBaseUrl string = ''

@description('Set true once the Foodspace-- ApiKey secret exists in Key Vault. A Key Vault reference to a missing secret reaches the app as literal text, so the setting is only created when you say the secret is there.')
param foodspaceApiKeySecretExists bool = false

@description('Whether the background loop sends records to Foodspace. Keep false until Foodspace is reachable and its authentication is settled (docs/OPEN-DECISIONS.md).')
param foodspaceForwardingEnabled bool = false

@description('Apply EF migrations when the API starts. Fine for one instance; the migration lock makes an overlap during a restart safe. Switch to a pipeline step when the database is reachable from the runners.')
param migrateOnStartup bool = true

@description('Object id of the person who sets the secrets (az ad signed-in-user show --query id -o tsv). Granted Key Vault Secrets Officer, because Owner on the subscription does not include the data plane under RBAC. Empty to skip.')
param operatorObjectId string = ''

@description('Location for everything. Keep it the same across resources.')
param location string = resourceGroup().location

var isProd = environmentName == 'prod'
var unique = take(uniqueString(resourceGroup().id), 6)
var tags = {
  app: 'sa-harvest-api'
  environment: environmentName
}

var planTier = appServicePlanSku == 'F1' ? 'Free' : (startsWith(appServicePlanSku, 'B') ? 'Basic' : (startsWith(appServicePlanSku, 'S') ? 'Standard' : 'PremiumV3'))
// Free (and Shared) plans cannot keep the process warm.
var supportsAlwaysOn = appServicePlanSku != 'F1'

var keyVaultName = take('${namePrefix}-${environmentName}-kv-${unique}', 24)
var storageAccountName = take(toLower(replace('${namePrefix}${environmentName}st${unique}', '-', '')), 24)
var attachmentsContainer = 'attachments'
var allowedHosts = join(concat(['${webAppName}.azurewebsites.net'], extraAllowedHosts), ';')

// Built-in role definition ids (the same in every tenant).
var roleKeyVaultSecretsUser = '4633458b-17de-408a-b874-0445c86b69e6'
var roleKeyVaultSecretsOfficer = 'b86a8fe4-44ce-4948-aee5-eccb2c155cd7'
var roleStorageBlobDataContributor = 'ba92f5b4-2d11-453d-a403-e96b0029c9fe'

// A Key Vault reference: App Service reads the secret with the app's identity and hands it to the container as an
// environment variable, so the value is never in the image, the settings blob or the deployment history.
func secretRef(vault string, name string) string => '@Microsoft.KeyVault(VaultName=${vault};SecretName=${name})'

// ── Monitoring ───────────────────────────────────────────────────────────────────────────────────────────────────────
resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${namePrefix}-${environmentName}-logs-${unique}'
  location: location
  tags: tags
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
  }
}

resource insights 'Microsoft.Insights/components@2020-02-02' = {
  name: '${namePrefix}-${environmentName}-ai'
  location: location
  kind: 'web'
  tags: tags
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: logs.id
  }
}

// ── Key Vault ────────────────────────────────────────────────────────────────────────────────────────────────────────
resource vault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: keyVaultName
  location: location
  tags: tags
  properties: {
    tenantId: subscription().tenantId
    sku: { family: 'A', name: 'standard' }
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 90
    // Purge protection cannot be switched off once on, and it blocks reusing the name for 90 days after a delete: production only.
    enablePurgeProtection: isProd ? true : null
    publicNetworkAccess: 'Enabled' // no private endpoints on the free tier; access is by identity (RBAC), not by network
  }
}

// ── Storage for photos and signatures (private; the API reaches it by identity, never by key or public URL) ─────────────
resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  // The compiler cannot see through take/replace that this is always at least 3 characters; it is namePrefix + environment + "st" + a 6-character hash.
  #disable-next-line BCP334
  name: storageAccountName
  location: location
  tags: tags
  kind: 'StorageV2'
  sku: { name: isProd ? 'Standard_GRS' : 'Standard_LRS' }
  properties: {
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    defaultToOAuthAuthentication: true
    encryption: {
      services: { blob: { enabled: true } }
      keySource: 'Microsoft.Storage'
    }
  }
}

resource blobService 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
  parent: storage
  name: 'default'
  properties: {
    deleteRetentionPolicy: { enabled: true, days: 14 }
    containerDeleteRetentionPolicy: { enabled: true, days: 14 }
  }
}

resource attachments 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = {
  parent: blobService
  name: attachmentsContainer
  properties: {
    publicAccess: 'None'
  }
}

// ── The API ──────────────────────────────────────────────────────────────────────────────────────────────────────────
resource plan 'Microsoft.Web/serverfarms@2023-12-01' = {
  name: '${namePrefix}-${environmentName}-plan'
  location: location
  tags: tags
  kind: 'linux'
  sku: { name: appServicePlanSku, tier: planTier }
  properties: {
    reserved: true // Linux
  }
}

var secretSettings = concat(
  [
    { name: 'Jwt__SigningKey', value: secretRef(keyVaultName, 'Jwt--SigningKey') }
    { name: 'ConnectionStrings__Default', value: secretRef(keyVaultName, 'ConnectionStrings--Default') }
    { name: 'DOCKER_REGISTRY_SERVER_PASSWORD', value: secretRef(keyVaultName, 'Registry--PullToken') }
  ],
  foodspaceApiKeySecretExists ? [{ name: 'Foodspace__ApiKey', value: secretRef(keyVaultName, 'Foodspace--ApiKey') }] : []
)

var plainSettings = concat(
  [
    { name: 'WEBSITES_PORT', value: '8080' }
    { name: 'WEBSITES_ENABLE_APP_SERVICE_STORAGE', value: 'false' }
    { name: 'DOCKER_REGISTRY_SERVER_URL', value: 'https://${registryServer}' }
    { name: 'DOCKER_REGISTRY_SERVER_USERNAME', value: registryUsername }
    // Never "Development": that turns on the seeded test accounts and the OpenAPI page, and the API refuses to start with
    // the seeder outside Development anyway.
    { name: 'ASPNETCORE_ENVIRONMENT', value: isProd ? 'Production' : 'Staging' }
    // App Service terminates TLS in front of the container and has no fixed address range, so trust its forwarded headers
    // from anywhere; safe only because the container is reachable solely through that front end (Security__* in the API).
    { name: 'Security__KnownNetworks__0', value: '0.0.0.0/0' }
    { name: 'Security__KnownNetworks__1', value: '::/0' }
    { name: 'AllowedHosts', value: allowedHosts }
    { name: 'Database__MigrateOnStartup', value: string(migrateOnStartup) }
    { name: 'Foodspace__ForwardingEnabled', value: string(foodspaceForwardingEnabled) }
    { name: 'Storage__AccountUri', value: storage.properties.primaryEndpoints.blob }
    { name: 'Storage__Container', value: attachmentsContainer }
    { name: 'APPLICATIONINSIGHTS_CONNECTION_STRING', value: insights.properties.ConnectionString }
  ],
  empty(foodspaceBaseUrl) ? [] : [{ name: 'Foodspace__BaseUrl', value: foodspaceBaseUrl }]
)

resource webApp 'Microsoft.Web/sites@2023-12-01' = {
  name: webAppName
  location: location
  tags: tags
  kind: 'app,linux,container'
  identity: { type: 'SystemAssigned' }
  properties: {
    serverFarmId: plan.id
    httpsOnly: true
    clientAffinityEnabled: false
    siteConfig: {
      linuxFxVersion: 'DOCKER|${initialContainerImage}'
      alwaysOn: supportsAlwaysOn
      minTlsVersion: '1.2'
      ftpsState: 'Disabled'
      http20Enabled: true
      appSettings: concat(plainSettings, secretSettings)
    }
  }
}

// ── Who may read what (least privilege) ──────────────────────────────────────────────────────────────────────────────
resource appReadsSecrets 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(vault.id, webApp.id, roleKeyVaultSecretsUser)
  scope: vault
  properties: {
    principalId: webApp.identity.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roleKeyVaultSecretsUser)
  }
}

resource appWritesAttachments 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(attachments.id, webApp.id, roleStorageBlobDataContributor)
  scope: attachments
  properties: {
    principalId: webApp.identity.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roleStorageBlobDataContributor)
  }
}

resource operatorSetsSecrets 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(operatorObjectId)) {
  name: guid(vault.id, operatorObjectId, roleKeyVaultSecretsOfficer)
  scope: vault
  properties: {
    principalId: operatorObjectId
    principalType: 'User'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roleKeyVaultSecretsOfficer)
  }
}

output webAppName string = webApp.name
output apiHost string = webApp.properties.defaultHostName
output apiBaseUrl string = 'https://${webApp.properties.defaultHostName}/'
output keyVaultName string = vault.name
output storageAccountName string = storage.name
output webAppPrincipalId string = webApp.identity.principalId
