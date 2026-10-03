using api.Data;
using api.Models;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace api.Services.Foodspace;

public interface ICboCollectionForwarder
{
    /// <summary>
    /// Forwards one collection now, whatever its schedule (used right after ingestion). Returns the resulting status, or
    /// null if there is no such collection.
    /// </summary>
    Task<ForwardingStatus?> ForwardAsync(string collectionId, CancellationToken cancellationToken = default);

    /// <summary>
    /// An Admin's manual retry (#50): forwards the collection now AND starts its attempt count again, so if Foodspace is
    /// still failing it gets a fresh automatic-retry budget instead of staying stuck at "retries used up". Also releases a
    /// held suspected duplicate: the Admin has decided it is a real collection. Returns the resulting status, or null if
    /// there is no such collection.
    /// </summary>
    Task<ForwardingStatus?> RetryAsync(string collectionId, CancellationToken cancellationToken = default);

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

    public async Task<ForwardingStatus?> RetryAsync(string collectionId, CancellationToken cancellationToken = default)
    {
        var collection = await _db.CboCollections.Include(c => c.ProductLines)
            .SingleOrDefaultAsync(c => c.Id == collectionId, cancellationToken);
        if (collection is null) return null;

        collection.SyncAttempts = 0;
        await AttemptAsync(collection, cancellationToken);
        return collection.ForwardingStatus;
    }

    public async Task<int> ForwardDueAsync(CancellationToken cancellationToken = default)
    {
        var now = _time.GetUtcNow();
        var due = await _db.CboCollections.Include(c => c.ProductLines)
            .Where(c => c.ForwardClaimedUntil == null || c.ForwardClaimedUntil <= now) // not being sent by someone else right now
            .Where(c => (c.ForwardingStatus == ForwardingStatus.Pending
                         && c.DuplicateOfId == null) // a suspected duplicate is held for an Admin and never auto-forwarded...
                        || (c.ForwardingStatus == ForwardingStatus.SyncedLocalPendingFoodspace // ...but once released it retries like any other
                            && c.NextForwardAttemptAt != null
                            && c.NextForwardAttemptAt <= now))
            .OrderBy(c => c.ReceivedAt)
            .Take(_options.BatchSize)
            .ToListAsync(cancellationToken);

        var attempted = 0;
        foreach (var collection in due)
        {
            if (await AttemptAsync(collection, cancellationToken)) attempted++;
        }
        return attempted;
    }

    /// <summary>Sends one collection if this caller wins the claim on it (see <see cref="ForwardingClaim"/>); false if somebody else has it.</summary>
    private async Task<bool> AttemptAsync(CboCollection collection, CancellationToken cancellationToken)
    {
        var now = _time.GetUtcNow();
        if (!await ForwardingClaim.TryClaimAsync(_db, collection, now, ForwardingClaim.LeaseFor(_options), cancellationToken))
        {
            _logger.LogInformation("Collection {Id} is already being forwarded by another worker; skipped.", collection.Id);
            return false;
        }

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

        // Saved per record so progress is never lost if a later record in the batch throws. Also ends the claim.
        if (!await ForwardingClaim.ReleaseAsync(_db, collection, cancellationToken))
        {
            _logger.LogWarning(
                "Collection {Id}: the claim ran out while Foodspace was answering and another worker took the record over; " +
                "this attempt's outcome was not saved.", collection.Id);
        }
        return true;
    }

    /// <summary>Base delay doubled for every failed attempt so far, capped.</summary>
    public TimeSpan BackoffFor(int attempts)
    {
        return ForwardingBackoff.For(_options, attempts);
    }
}
