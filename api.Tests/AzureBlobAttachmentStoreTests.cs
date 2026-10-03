using api.Services.Attachments;
using Azure.Storage.Blobs;

namespace api.Tests;

/// <summary>
/// Runs a [Fact] only when TEST_AZURITE is set to a storage connection string for the Azurite emulator (the same Azure SDK code
/// path the API uses in Azure, against a local blob service). Without it the test is skipped, so a developer without Docker still
/// gets a green build; CI always starts Azurite and sets it.
///
///   docker run -d --name sah-azurite -p 127.0.0.1:10000:10000 mcr.microsoft.com/azure-storage/azurite azurite-blob --blobHost 0.0.0.0 --skipApiVersionCheck
///   TEST_AZURITE="UseDevelopmentStorage=true" dotnet test api.Tests
/// </summary>
public sealed class AzuriteFactAttribute : FactAttribute
{
    public const string Variable = "TEST_AZURITE";

    public AzuriteFactAttribute()
    {
        if (string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable(Variable)))
            Skip = $"Set {Variable} to an Azurite connection string to run this test (see AzuriteFactAttribute).";
    }
}

/// <summary>The Azure implementation of the attachment store, against the Azurite emulator (never a real storage account).</summary>
public class AzureBlobAttachmentStoreTests
{
    private static string ConnectionString => Environment.GetEnvironmentVariable(AzuriteFactAttribute.Variable)!;

    // A fresh container per test, so tests never see each other's blobs.
    private static string NewContainerName() => "att-" + Guid.NewGuid().ToString("N")[..16];

    private static async Task<byte[]> ReadAll(AttachmentContent content)
    {
        await using var stream = content.Content;
        using var copy = new MemoryStream();
        await stream.CopyToAsync(copy);
        return copy.ToArray();
    }

    [AzuriteFact]
    public async Task AFile_RoundTrips_WithItsTypeAndLength_AndTheContainerIsCreatedOnFirstUse()
    {
        var store = AzureBlobAttachmentStore.ForEmulator(ConnectionString, NewContainerName());
        var bytes = new byte[] { 0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4, 5 };

        await store.PutAsync("c1/a1", new MemoryStream(bytes), "image/jpeg", default);
        var read = await store.OpenReadAsync("c1/a1", default);

        Assert.NotNull(read);
        Assert.Equal("image/jpeg", read!.ContentType);
        Assert.Equal(bytes.Length, read.Length);
        Assert.Equal(bytes, await ReadAll(read));
    }

    [AzuriteFact]
    public async Task APutReplacesWhatWasThere_SoARetriedUploadIsHarmless()
    {
        var store = AzureBlobAttachmentStore.ForEmulator(ConnectionString, NewContainerName());

        await store.PutAsync("c1/a1", new MemoryStream(new byte[] { 1, 1, 1 }), "image/png", default);
        await store.PutAsync("c1/a1", new MemoryStream(new byte[] { 2, 2 }), "image/png", default);

        Assert.Equal(new byte[] { 2, 2 }, await ReadAll((await store.OpenReadAsync("c1/a1", default))!));
    }

    [AzuriteFact]
    public async Task AMissingFile_IsNull_NotAnError()
    {
        var store = AzureBlobAttachmentStore.ForEmulator(ConnectionString, NewContainerName());

        await store.PutAsync("c1/a1", new MemoryStream(new byte[] { 1 }), "image/png", default); // makes the container exist

        Assert.Null(await store.OpenReadAsync("c1/no-such-file", default));
    }

    [AzuriteFact]
    public async Task ADeletedFile_IsGone_AndDeletingAMissingOneIsFine()
    {
        var store = AzureBlobAttachmentStore.ForEmulator(ConnectionString, NewContainerName());
        await store.PutAsync("c1/a1", new MemoryStream(new byte[] { 1 }), "image/png", default);

        await store.DeleteAsync("c1/a1", default);
        await store.DeleteAsync("c1/a1", default); // already gone: not an error

        Assert.Null(await store.OpenReadAsync("c1/a1", default));
    }

    [AzuriteFact]
    public async Task TheStoredFile_IsNeverCacheable_AndNobodyCanReadItAnonymously()
    {
        var container = NewContainerName();
        var store = AzureBlobAttachmentStore.ForEmulator(ConnectionString, container);
        await store.PutAsync("c1/a1", new MemoryStream(new byte[] { 1, 2 }), "image/png", default);

        var blob = new BlobContainerClient(ConnectionString, container).GetBlobClient("c1/a1");
        var properties = (await blob.GetPropertiesAsync()).Value;
        var access = (await new BlobContainerClient(ConnectionString, container).GetAccessPolicyAsync()).Value;

        Assert.Equal("private, no-store", properties.CacheControl);
        Assert.Equal(Azure.Storage.Blobs.Models.PublicAccessType.None, access.BlobPublicAccess); // the container is private
    }

    [AzuriteFact]
    public async Task AnUnreachableStorageAccount_BecomesAnAttachmentStoreException_NotARawSdkException()
    {
        // Nothing listens on this port: the SDK retries and gives up; the store must translate whatever it throws.
        var store = AzureBlobAttachmentStore.ForEmulator(
            "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;" +
            "AccountKey=" + Convert.ToBase64String(new byte[32]) + ";BlobEndpoint=http://127.0.0.1:1/devstoreaccount1;",
            NewContainerName());

        await Assert.ThrowsAsync<AttachmentStoreException>(() => store.PutAsync("c1/a1", new MemoryStream(new byte[] { 1 }), "image/png", default));
    }
}
