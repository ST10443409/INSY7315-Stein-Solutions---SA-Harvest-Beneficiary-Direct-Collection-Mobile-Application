namespace api.Models;

/// <summary>Room: VettingDecision (table "vetting_decisions"), the Form 2 outcome.</summary>
public class VettingDecision : ForwardedEntity
{
    /// <summary>
    /// The Foodspace beneficiary this decision is about. Deliberately NOT a foreign key: the
    /// beneficiary table is a cache that is replaced on every fetch, and a decision must survive that.
    /// </summary>
    public required string FoodspaceRecordId { get; set; }

    public DecisionOutcome Outcome { get; set; }
    public string? Notes { get; set; }

    /// <summary>Username of the vetting officer (a string, as on the device).</summary>
    public required string OfficerId { get; set; }

    /// <summary>Epoch milliseconds (Room: Long).</summary>
    public long DecisionTimestamp { get; set; }

    /// <summary>
    /// Room: retryCount. How many times the device's sync attempts for this decision failed. Device bookkeeping, kept here
    /// only so the two schemas stay identical; the sync endpoint does not read or write it.
    /// </summary>
    public int RetryCount { get; set; }

    /// <summary>
    /// Room: syncErrorCode. The error code the device last got for this decision (e.g. VALIDATION_FAILED), so it can tell
    /// the officer why it did not sync. Device bookkeeping, like <see cref="RetryCount"/>; the sync endpoint does not read
    /// or write it.
    /// </summary>
    public string? SyncErrorCode { get; set; }
}
