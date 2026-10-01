using api.Data;
using api.Models;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace api.Services.Foodspace;

public interface IVettingDecisionForwarder
{
    /// <summary>
    /// Forwards one decision now, whatever its schedule. Returns the resulting status, or null if there is no such
    /// decision. A decision that has been superseded is not sent, even manually.
    /// </summary>
    Task<ForwardingStatus?> ForwardAsync(string decisionId, CancellationToken cancellationToken = default);

    /// <summary>
    /// An Admin's manual retry (#50): like <see cref="ForwardAsync"/>, and it also starts the decision's attempt count
    /// again, so if Foodspace is still failing it gets a fresh automatic-retry budget. A decision that has been
    /// superseded is still not sent. Returns the resulting status, or null if there is no such decision.
    /// </summary>
    Task<ForwardingStatus?> RetryAsync(string decisionId, CancellationToken cancellationToken = default);

    /// <summary>Forwards every decision that is due (new, or a scheduled retry whose time has come). Returns how many were looked at.</summary>
    Task<int> ForwardDueAsync(CancellationToken cancellationToken = default);
}

/// <summary>
/// Forwards vetting decisions that are already stored here to Foodspace, the same way
/// <see cref="CboCollectionForwarder"/> forwards collections: the decision is saved first, so a Foodspace problem can
/// only delay forwarding (it moves to SyncedLocalPendingFoodspace and is retried with exponential backoff; it is
/// never deleted or marked failed).
///
/// One difference: only the officer's LATEST decision on a beneficiary is sent. An officer who flags a record and later
/// approves it should leave Foodspace saying "approved", not briefly "flagged" or, after a retry, "flagged" again. A
/// decision with a newer one on the same beneficiary is marked Superseded and never sent (it stays here as history).
/// "Newer" is by decision time, then by when the device created it, whoever made the newer decision.
/// </summary>
public class VettingDecisionForwarder : IVettingDecisionForwarder
{
    private readonly AppDbContext _db;
    private readonly IFoodspaceApiClient _client;
    private readonly FoodspaceOptions _options;
    private readonly TimeProvider _time;
    private readonly ILogger<VettingDecisionForwarder> _logger;

    public VettingDecisionForwarder(
        AppDbContext db,
        IFoodspaceApiClient client,
        IOptions<FoodspaceOptions> options,
        TimeProvider time,
        ILogger<VettingDecisionForwarder> logger)
    {
        _db = db;
        _client = client;
        _options = options.Value;
        _time = time;
        _logger = logger;
    }

    public async Task<ForwardingStatus?> ForwardAsync(string decisionId, CancellationToken cancellationToken = default)
    {
        var decision = await _db.VettingDecisions.SingleOrDefaultAsync(d => d.Id == decisionId, cancellationToken);
        if (decision is null) return null;

        await AttemptAsync(decision, cancellationToken);
        return decision.ForwardingStatus;
    }

    public async Task<ForwardingStatus?> RetryAsync(string decisionId, CancellationToken cancellationToken = default)
    {
        var decision = await _db.VettingDecisions.SingleOrDefaultAsync(d => d.Id == decisionId, cancellationToken);
        if (decision is null) return null;

        decision.SyncAttempts = 0;
        await AttemptAsync(decision, cancellationToken);
        return decision.ForwardingStatus;
    }

    public async Task<int> ForwardDueAsync(CancellationToken cancellationToken = default)
    {
        var now = _time.GetUtcNow();
        var due = await _db.VettingDecisions
            .Where(d => d.ForwardingStatus == ForwardingStatus.Pending
                        || (d.ForwardingStatus == ForwardingStatus.SyncedLocalPendingFoodspace
                            && d.NextForwardAttemptAt != null
                            && d.NextForwardAttemptAt <= now))
            .OrderBy(d => d.DecisionTimestamp) // oldest first: Foodspace then ends with the newest as its last word
            .ThenBy(d => d.ReceivedAt)
            .Take(_options.BatchSize)
            .ToListAsync(cancellationToken);

        foreach (var decision in due)
        {
            await AttemptAsync(decision, cancellationToken);
        }
        return due.Count;
    }

    private async Task AttemptAsync(VettingDecision decision, CancellationToken cancellationToken)
    {
        if (await HasNewerDecisionAsync(decision, cancellationToken))
        {
            decision.ForwardingStatus = ForwardingStatus.Superseded;
            decision.NextForwardAttemptAt = null;
            decision.SyncError = null;
            await _db.SaveChangesAsync(cancellationToken);
            _logger.LogInformation("Vetting decision {Id} is superseded by a newer one and was not forwarded.", decision.Id);
            return;
        }

        var now = _time.GetUtcNow();
        decision.SyncAttempts++;
        decision.LastSyncAttemptAt = now;

        var result = await _client.SubmitVettingDecisionAsync(decision, cancellationToken);

        if (result.Outcome == FoodspaceOutcome.Success)
        {
            decision.ForwardingStatus = ForwardingStatus.Forwarded;
            decision.NextForwardAttemptAt = null;
            decision.SyncError = null;
        }
        else
        {
            decision.ForwardingStatus = ForwardingStatus.SyncedLocalPendingFoodspace;
            decision.SyncError = result.Error;
            // Rejected data won't be accepted on a retry, and exhausted retries stop too: both wait for an Admin.
            var retryable = result.Outcome == FoodspaceOutcome.Transient && decision.SyncAttempts < _options.MaxAttempts;
            decision.NextForwardAttemptAt = retryable ? now + ForwardingBackoff.For(_options, decision.SyncAttempts) : null;
            _logger.LogWarning(
                "Vetting decision {Id} is saved but not yet in Foodspace (attempt {Attempts}, next retry {Next})",
                decision.Id, decision.SyncAttempts, decision.NextForwardAttemptAt?.ToString("O") ?? "none");
        }

        // Saved per decision so progress is never lost if a later one in the batch throws.
        await _db.SaveChangesAsync(cancellationToken);
    }

    private Task<bool> HasNewerDecisionAsync(VettingDecision d, CancellationToken cancellationToken) =>
        _db.VettingDecisions.AnyAsync(o =>
            o.FoodspaceRecordId == d.FoodspaceRecordId
            && o.Id != d.Id
            && (o.DecisionTimestamp > d.DecisionTimestamp
                || (o.DecisionTimestamp == d.DecisionTimestamp && o.CreatedAt > d.CreatedAt)),
            cancellationToken);
}
