using Azure;
using Azure.Identity;
using Azure.Storage.Blobs;
using Azure.Storage.Blobs.Models;

namespace api.Services.Attachments;

/// <summary>
/// Azure Blob Storage behind <see cref="IAttachmentStore"/>. Works on ONE private container and only reads, writes and deletes
/// blobs in it: the identity's role is granted on that container alone, so this class never asks for anything wider (it does not
/// create the container, list the account or generate SAS links, which would need more permission).
/// </summary>
public sealed class AzureBlobAttachmentStore : IAttachmentStore
{
    private readonly BlobContainerClient _container;
    private readonly bool _createContainer;
    private readonly SemaphoreSlim _createLock = new(1, 1);
    private bool _containerReady;

    /// <param name="createContainerIfMissing">Only for the local emulator, which starts empty. In Azure the container exists and the identity may not create one.</param>
    public AzureBlobAttachmentStore(BlobContainerClient container, bool createContainerIfMissing = false)
    {
        _container = container;
        _createContainer = createContainerIfMissing;
    }

    // The SDK's defaults (6 tries with growing waits) take over half a minute to give up on an unreachable account, longer than the
    // phone waits for an answer. Two retries is enough to ride out a blip; after that the caller answers 503 and the app tries again
    // on its next run, which is the retry that matters on a flaky link.
    private static BlobClientOptions ClientOptions() => new()
    {
        Retry =
        {
            MaxRetries = 2,
            Delay = TimeSpan.FromMilliseconds(500),
            MaxDelay = TimeSpan.FromSeconds(4),
            NetworkTimeout = TimeSpan.FromSeconds(30),
        },
    };

    /// <summary>Azure: the container's address plus the app's managed identity (DefaultAzureCredential: no key, no secret).</summary>
    public static AzureBlobAttachmentStore ForAccount(Uri accountUri, string container) =>
        new(new BlobContainerClient(new Uri(accountUri, container), new DefaultAzureCredential(), ClientOptions()));

    /// <summary>Local development: the Azurite emulator through its connection string.</summary>
    public static AzureBlobAttachmentStore ForEmulator(string connectionString, string container) =>
        new(new BlobContainerClient(connectionString, container, ClientOptions()), createContainerIfMissing: true);

    public async Task PutAsync(string blobName, Stream content, string contentType, CancellationToken cancellationToken)
    {
        await Guard(async () =>
        {
            await EnsureContainerAsync(cancellationToken);
            var options = new BlobUploadOptions
            {
                HttpHeaders = new BlobHttpHeaders { ContentType = contentType, CacheControl = "private, no-store" },
            };
            await _container.GetBlobClient(blobName).UploadAsync(content, options, cancellationToken);
        });
    }

    public async Task<AttachmentContent?> OpenReadAsync(string blobName, CancellationToken cancellationToken)
    {
        return await Guard<AttachmentContent?>(async () =>
        {
            try
            {
                var result = await _container.GetBlobClient(blobName).DownloadStreamingAsync(cancellationToken: cancellationToken);
                var details = result.Value.Details;
                return new AttachmentContent(result.Value.Content, details.ContentType, details.ContentLength);
            }
            catch (RequestFailedException ex) when (ex.Status == 404)
            {
                return null;
            }
        });
    }

    public async Task DeleteAsync(string blobName, CancellationToken cancellationToken)
    {
        await Guard(async () => await _container.GetBlobClient(blobName).DeleteIfExistsAsync(cancellationToken: cancellationToken));
    }

    private async Task EnsureContainerAsync(CancellationToken cancellationToken)
    {
        if (!_createContainer || _containerReady) return;
        await _createLock.WaitAsync(cancellationToken);
        try
        {
            if (_containerReady) return;
            await _container.CreateIfNotExistsAsync(PublicAccessType.None, cancellationToken: cancellationToken);
            _containerReady = true;
        }
        finally
        {
            _createLock.Release();
        }
    }

    // Anything the Azure SDK throws for "cannot reach or not allowed" becomes one exception type the service understands.
    // The inner exception keeps the detail for the log; it never reaches a client. Note the SDK reports "gave up after its retries"
    // as an AggregateException (found by running the real SDK against an unreachable endpoint), so that is caught too.
    private static bool IsStorageFailure(Exception ex) =>
        ex is RequestFailedException or AuthenticationFailedException or HttpRequestException or TimeoutException or AggregateException;

    private static async Task Guard(Func<Task> action)
    {
        try
        {
            await action();
        }
        catch (Exception ex) when (IsStorageFailure(ex))
        {
            throw new AttachmentStoreException("Blob storage request failed: " + ex.GetType().Name, ex);
        }
    }

    private static async Task<T> Guard<T>(Func<Task<T>> action)
    {
        try
        {
            return await action();
        }
        catch (Exception ex) when (IsStorageFailure(ex))
        {
            throw new AttachmentStoreException("Blob storage request failed: " + ex.GetType().Name, ex);
        }
    }
}
