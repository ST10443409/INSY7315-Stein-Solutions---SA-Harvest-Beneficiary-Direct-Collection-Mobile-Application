namespace api.Models;

/// <summary>Which kind of record an Admin action was about. Wire value: UPPER_SNAKE_CASE (<c>CBO_COLLECTION</c>).</summary>
public enum SyncForm
{
    CboCollection,
    VettingDecision
}

public enum AdminActionType
{
    /// <summary>Forward the record to Foodspace now, with a fresh automatic-retry budget.</summary>
    Retry,

    /// <summary>Stop trying: the Admin has dealt with it outside the app, or confirmed it is a duplicate.</summary>
    Dismiss
}

/// <summary>
/// The state an Admin sees a record in. Derived from <see cref="ForwardingStatus"/> plus whether a retry is scheduled and
/// whether the record is a held suspected duplicate; see <see cref="SyncStates.Of"/>. The one definition, shared by the
/// monitor's counts (#49) and the resolution tooling (#50), so the two always agree. Wire value: UPPER_SNAKE_CASE.
/// </summary>
public enum SyncState
{
    /// <summary>Reached this backend, not yet sent to Foodspace.</summary>
    Waiting,

    /// <summary>Foodspace has not accepted it and an automatic retry is scheduled.</summary>
    Retrying,

    /// <summary>Foodspace has not accepted it and no retry is scheduled (rejected, or retries ran out). Waits for an Admin.</summary>
    NeedsAttention,

    Forwarded,

    /// <summary>Form 1 only: looks like a collection already recorded, so it is held (never sent) until an Admin releases or dismisses it.</summary>
    DuplicateHeld,

    /// <summary>Form 2 only: a newer decision on the same beneficiary replaced it. History, never sent.</summary>
    Superseded,

    /// <summary>An Admin chose not to send it. Kept for the audit trail.</summary>
    Dismissed
}

public static class SyncStates
{
    public static SyncState Of(ForwardingStatus status, bool retryScheduled, bool suspectedDuplicate) => status switch
    {
        // A suspected duplicate is only "held" while it is still Pending. Once an Admin releases it, it is an ordinary record.
        ForwardingStatus.Pending => suspectedDuplicate ? SyncState.DuplicateHeld : SyncState.Waiting,
        ForwardingStatus.SyncedLocalPendingFoodspace => retryScheduled ? SyncState.Retrying : SyncState.NeedsAttention,
        ForwardingStatus.Forwarded => SyncState.Forwarded,
        ForwardingStatus.Superseded => SyncState.Superseded,
        ForwardingStatus.Dismissed => SyncState.Dismissed,
        _ => throw new ArgumentOutOfRangeException(nameof(status), status, "Unknown forwarding status.")
    };

    /// <summary>The states that need an Admin: what the failed-sync list shows.</summary>
    public static bool NeedsAdmin(this SyncState state) => state is SyncState.NeedsAttention or SyncState.DuplicateHeld;
}

/// <summary>
/// SERVER-ONLY audit trail ("admin_actions"): who retried or dismissed which record, when, and what came of it.
/// Rows are only ever added.
/// </summary>
public class AdminAction
{
    public Guid Id { get; set; }

    public DateTimeOffset OccurredAt { get; set; }

    /// <summary>Username of the Admin, taken from the token.</summary>
    public required string Admin { get; set; }

    public AdminActionType Action { get; set; }

    public SyncForm Form { get; set; }

    /// <summary>The record acted on. No FK: it can be either table, and the trail must outlive any change to the record.</summary>
    public required string RecordId { get; set; }

    public ForwardingStatus PreviousStatus { get; set; }

    public ForwardingStatus ResultStatus { get; set; }

    /// <summary>How many forwarding attempts the record had before the action (a retry starts the count again).</summary>
    public int PreviousAttempts { get; set; }

    /// <summary>Why, for a dismissal. Null for a retry.</summary>
    public string? Reason { get; set; }
}
