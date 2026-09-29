namespace api.Models;

// Mirror the Kotlin enums in client/.../data/local/entity/. The database (and JSON) values are the
// UPPER_CASE names Room stores (e.g. "PENDING"); see UpperCaseEnumConverter in Data/.

/// <summary>
/// Mobile: whether the record reached this backend. Server: whether it has been forwarded to Foodspace.
/// </summary>
public enum SyncStatus
{
    Pending,
    Synced,
    Failed
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
