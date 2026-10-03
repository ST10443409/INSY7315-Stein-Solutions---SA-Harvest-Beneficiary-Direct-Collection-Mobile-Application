using System.Collections.Concurrent;
using api.Services.Attachments;

namespace api.Tests;

/// <summary>The tests' stand-in for Azure Blob Storage: bytes in a dictionary, with switches to simulate an outage.</summary>
public sealed class InMemoryAttachmentStore : IAttachmentStore
{
    private readonly ConcurrentDictionary<string, (byte[] Bytes, string ContentType)> _blobs = new();

    /// <summary>While true every call fails like an unreachable storage account.</summary>
    public bool Unavailable { get; set; }

    public int PutCount;

    public IReadOnlyCollection<string> Names => _blobs.Keys.ToList();

    public byte[]? BytesOf(string blobName) => _blobs.TryGetValue(blobName, out var blob) ? blob.Bytes : null;

    public string? ContentTypeOf(string blobName) => _blobs.TryGetValue(blobName, out var blob) ? blob.ContentType : null;

    public async Task PutAsync(string blobName, Stream content, string contentType, CancellationToken cancellationToken)
    {
        if (Unavailable) throw new AttachmentStoreException("simulated outage");
        using var copy = new MemoryStream();
        await content.CopyToAsync(copy, cancellationToken);
        _blobs[blobName] = (copy.ToArray(), contentType);
        Interlocked.Increment(ref PutCount);
    }

    public Task<AttachmentContent?> OpenReadAsync(string blobName, CancellationToken cancellationToken)
    {
        if (Unavailable) throw new AttachmentStoreException("simulated outage");
        return Task.FromResult<AttachmentContent?>(_blobs.TryGetValue(blobName, out var blob)
            ? new AttachmentContent(new MemoryStream(blob.Bytes, writable: false), blob.ContentType, blob.Bytes.Length)
            : null);
    }

    public Task DeleteAsync(string blobName, CancellationToken cancellationToken)
    {
        if (Unavailable) throw new AttachmentStoreException("simulated outage");
        _blobs.TryRemove(blobName, out _);
        return Task.CompletedTask;
    }
}
