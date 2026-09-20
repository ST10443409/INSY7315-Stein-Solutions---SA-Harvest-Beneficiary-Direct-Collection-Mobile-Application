using System.Threading.Channels;

namespace api.Services;

public class SyncPayload
{
    public string Id { get; set; } = string.Empty;
    public string Data { get; set; } = string.Empty;
}

public interface IQueueService
{
    ValueTask QueueSyncPayloadAsync(SyncPayload payload, CancellationToken cancellationToken = default);
    IAsyncEnumerable<SyncPayload> DequeueAsync(CancellationToken cancellationToken);
}

public class QueueService : IQueueService
{
    private readonly Channel<SyncPayload> _queue;

    public QueueService()
    {
        var options = new BoundedChannelOptions(1000)
        {
            FullMode = BoundedChannelFullMode.Wait
        };
        _queue = Channel.CreateBounded<SyncPayload>(options);
    }

    public async ValueTask QueueSyncPayloadAsync(SyncPayload payload, CancellationToken cancellationToken = default)
    {
        await _queue.Writer.WriteAsync(payload, cancellationToken);
    }

    public IAsyncEnumerable<SyncPayload> DequeueAsync(CancellationToken cancellationToken)
    {
        return _queue.Reader.ReadAllAsync(cancellationToken);
    }
}
