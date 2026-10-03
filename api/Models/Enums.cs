namespace api.Models;

// Mirror the Kotlin enums in client/.../data/local/entity/. The database (and JSON) values are the
// UPPER_CASE names Room stores (e.g. "PENDING"); see UpperCaseEnumConverter in Data/.

/// <summary>
/// Whether the record reached this backend (the same meaning as on the device). Where the record
/// stands with Foodspace is tracked separately, in <see cref="ForwardingStatus"/>.
/// </summary>
public enum SyncStatus
{
    Pending,
    Synced,
    Failed
}

/// <summary>
/// SERVER-ONLY: where a record that reached this backend stands with Foodspace. Stored as UPPER_SNAKE_CASE
/// ("SYNCED_LOCAL_PENDING_FOODSPACE"). Not a Room enum, so it is not part of the Room parity check.
/// </summary>
public enum ForwardingStatus
{
    /// <summary>Received here, not yet sent to Foodspace.</summary>
    Pending,

    /// <summary>Foodspace accepted it.</summary>
    Forwarded,

    /// <summary>
    /// Safe on this backend but Foodspace has not accepted it (outage, rejection). Retried automatically with
    /// backoff; once retries run out it stays here, visible to Admins (#49/#50) for a manual retry. It is
    /// deliberately not a "failed" record: nothing is lost and the collector never has to re-enter anything.
    /// </summary>
    SyncedLocalPendingFoodspace,

    /// <summary>
    /// Vetting decisions only. A newer decision on the same beneficiary exists, so this one is history and is never sent
    /// to Foodspace (Foodspace only ever sees the officer's latest word). Kept on our side as the audit trail.
    /// </summary>
    Superseded,

    /// <summary>
    /// An Admin chose not to send it (#50): a rejected record they dealt with outside the app, or a suspected duplicate they
    /// confirmed. Never sent, kept as the audit trail.
    /// </summary>
    Dismissed
}

/// <summary>
/// What a stored picture is of. The same four values as the device's AttachmentKind; wire and database form is UPPER_SNAKE_CASE
/// (<c>DONOR_SIGNATURE</c>).
/// </summary>
public enum AttachmentKind
{
    /// <summary>The donor's finger-drawn signature (at most one per collection).</summary>
    DonorSignature,

    /// <summary>The CBO's finger-drawn signature (at most one per collection).</summary>
    CboSignature,

    /// <summary>One of the donation photos; the slot says which numbered shot.</summary>
    Photo,

    /// <summary>The photo of the paper delivery note (at most one per collection).</summary>
    DeliveryNote
}

public enum DecisionOutcome
{
    Approve,
    Reject,
    Flag
}

public enum Tone
{
    Ok,
    Warn,
    New
}
