namespace api.Services.Attachments;

/// <summary>A stored file being read back. The caller disposes <see cref="Content"/>.</summary>
public sealed record AttachmentContent(Stream Content, string ContentType, long Length);

/// <summary>
/// The storage could not be reached or is not usable right now (not configured, no permission, outage). Never carries file
/// content; the message is for logs. The caller answers 503 and the app tries again later.
/// </summary>
public class AttachmentStoreException : Exception
{
    public AttachmentStoreException(string message, Exception? inner = null) : base(message, inner) { }
}

/// <summary>
/// Where the bytes of signatures and photos live. Names are chosen by the server (see <c>AttachmentService</c>), never by the
/// client. Azure Blob Storage in a real environment, an in-memory fake in the tests.
/// </summary>
public interface IAttachmentStore
{
    /// <summary>Stores <paramref name="content"/> under <paramref name="blobName"/>, replacing anything already there (so a retried upload is harmless).</summary>
    Task PutAsync(string blobName, Stream content, string contentType, CancellationToken cancellationToken);

    /// <summary>The stored file, or null when there is none under that name.</summary>
    Task<AttachmentContent?> OpenReadAsync(string blobName, CancellationToken cancellationToken);

    /// <summary>Removes the file; a missing file is not an error.</summary>
    Task DeleteAsync(string blobName, CancellationToken cancellationToken);
}

/// <summary>Used when no storage is configured: every call says so, instead of the API refusing to start.</summary>
public sealed class NotConfiguredAttachmentStore : IAttachmentStore
{
    private static AttachmentStoreException NotConfigured() =>
        new("Attachment storage is not configured (set Storage:AccountUri).");

    public Task PutAsync(string blobName, Stream content, string contentType, CancellationToken cancellationToken) =>
        throw NotConfigured();

    public Task<AttachmentContent?> OpenReadAsync(string blobName, CancellationToken cancellationToken) => throw NotConfigured();

    public Task DeleteAsync(string blobName, CancellationToken cancellationToken) => throw NotConfigured();
}
