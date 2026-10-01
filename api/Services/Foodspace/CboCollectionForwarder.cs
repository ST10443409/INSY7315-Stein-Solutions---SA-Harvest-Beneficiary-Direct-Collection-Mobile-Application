using api.Data;
using api.Models;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace api.Services.Foodspace;

public interface ICboCollectionForwarder
{
    /// <summary>
    /// Forwards one collection now, whatever its schedule (used right after ingestion, and for an Admin's
    /// manual retry). Returns the resulting status, or null if there is no such collection.
    /// </summary>
    Task<ForwardingStatus?> ForwardAsync(string collectionId, CancellationToken cancellationToken = default);

    /// <summary>Forwards every collection that is due (new, or a scheduled retry whose time has come). Returns how many were attempted.</summary>
    Task<int> ForwardDueAsync(CancellationToken cancellationToken = default);
}

/// <summary>
/// Forwards collections that are already stored here to Foodspace. The record is saved first, so a
/// Foodspace problem can only delay forwarding: it moves the record to SyncedLocalPendingFoodspace
/// and schedules a retry with exponential backoff. It never deletes or fails the record.
/// </summary>
public class CboCollectionForwarder : ICboCollectionForwarder
{
    private readonly AppDbContext _db;
    private readonly IFoodspaceApiClient _client;
    private readonly FoodspaceOptions _options;
    private readonly TimeProvider _time;
    private readonly ILogger<CboCollectionForwarder> _logger;

    public CboCollectionForwarder(
        AppDbContext db,
        IFoodspaceApiClient client,
        IOptions<FoodspaceOptions> options,
        TimeProvider time,
        ILogger<CboCollectionForwarder> logger)
    {
        _db = db;
        _client = client;
        _options = options.Value;
        _time = time;
        _logger = logger;
    }

    public async Task<ForwardingStatus?> ForwardAsync(string collectionId, CancellationToken cancellationToken = default)
    {
        var collection = await _db.CboCollections.Include(c => c.ProductLines)
            .SingleOrDefaultAsync(c => c.Id == collectionId, cancellationToken);
        if (collection is null) return null;

        await AttemptAsync(collection, cancellationToken);
        return collection.ForwardingStatus;
    }

    public async Task<int> ForwardDueAsync(CancellationToken cancellationToken = default)
    {
        var now = _time.GetUtcNow();
        var due = await _db.CboCollections.Include(c => c.ProductLines)
            .Where(c => c.DuplicateOfId == null // suspected duplicates wait for Admin review, they are never auto-forwarded
                        && (c.ForwardingStatus == ForwardingStatus.Pending
                            || (c.ForwardingStatus == ForwardingStatus.SyncedLocalPendingFoodspace
                                && c.NextForwardAttemptAt != null
                                && c.NextForwardAttemptAt <= now)))
            .OrderBy(c => c.ReceivedAt)
            .Take(_options.BatchSize)
            .ToListAsync(cancellationToken);

        foreach (var collection in due)
        {
            await AttemptAsync(collection, cancellationToken);
        }
        return due.Count;
    }

    private async Task AttemptAsync(CboCollection collection, CancellationToken cancellationToken)
    {
        var now = _time.GetUtcNow();
        collection.SyncAttempts++;
        collection.LastSyncAttemptAt = now;

        var result = await _client.SubmitCboCollectionAsync(collection, cancellationToken);

        if (result.Outcome == FoodspaceOutcome.Success)
        {
            collection.ForwardingStatus = ForwardingStatus.Forwarded;
            collection.NextForwardAttemptAt = null;
            collection.SyncError = null;
        }
        else
        {
            collection.ForwardingStatus = ForwardingStatus.SyncedLocalPendingFoodspace;
            collection.SyncError = result.Error;
            // Rejected data won't be accepted on a retry, and exhausted retries stop too: both wait for an Admin.
            var retryable = result.Outcome == FoodspaceOutcome.Transient && collection.SyncAttempts < _options.MaxAttempts;
            collection.NextForwardAttemptAt = retryable ? now + BackoffFor(collection.SyncAttempts) : null;
            _logger.LogWarning(
                "Collection {Id} is saved but not yet in Foodspace (attempt {Attempts}, next retry {Next})",
                collection.Id, collection.SyncAttempts, collection.NextForwardAttemptAt?.ToString("O") ?? "none");
        }

        // Saved per record so progress is never lost if a later record in the batch throws.
        await _db.SaveChangesAsync(cancellationToken);
    }

    /// <summary>Base delay doubled for every failed attempt so far, capped.</summary>
    public TimeSpan BackoffFor(int attempts)
    {
        return ForwardingBackoff.For(_options, attempts);
    }
}
