namespace api.DTOs;

/// <summary>
/// One record that needs an Admin (rejected by Foodspace, retries used up, or a held suspected duplicate).
/// <see cref="Form"/> is <c>CBO_COLLECTION</c> or <c>VETTING_DECISION</c>; <see cref="State"/> is a <see cref="api.Models.SyncState"/>
/// name in UPPER_SNAKE_CASE. <see cref="Error"/> is what Foodspace answered (a status code, never record data).
/// </summary>
/// <param name="Label">A short line that identifies the record to a person: the donor, or the beneficiary and outcome.</param>
/// <param name="SubmittedBy">The collector (Form 1) or officer (Form 2) who sent it.</param>
public record SyncAttentionItem(
    string Id,
    string Form,
    string State,
    string Label,
    DateTimeOffset ReceivedAt,
    string? SubmittedBy,
    int SyncAttempts,
    DateTimeOffset? LastAttemptAt,
    string? Error);

/// <summary>A page of <see cref="SyncAttentionItem"/>, oldest first (the ones that have waited longest). Ask for <c>page + 1</c> while <c>hasMore</c> is true.</summary>
public record SyncAttentionResponse(IReadOnlyList<SyncAttentionItem> Items, int Page, int PageSize, int TotalCount, bool HasMore);

/// <summary>The record a suspected duplicate was matched against, so an Admin can compare the two before deciding.</summary>
public record DuplicateOfSummary(string Id, string Label, string State, DateTimeOffset ReceivedAt);

/// <summary>
/// One entry of a record's audit trail: <see cref="Action"/> is <c>RETRY</c> or <c>DISMISS</c>; <see cref="ResultStatus"/> is the
/// record's forwarding status afterwards (<c>FORWARDED</c>, <c>SYNCED_LOCAL_PENDING_FOODSPACE</c>, <c>DISMISSED</c>, ...).
/// </summary>
public record AdminActionEntry(DateTimeOffset At, string Admin, string Action, string? Reason, string ResultStatus);

/// <summary>
/// Everything an Admin needs to decide what to do with one record, including the error detail, plus what they may do
/// (<see cref="CanRetry"/>, <see cref="CanDismiss"/>) and what has already been done to it (<see cref="History"/>, newest first).
/// </summary>
public record SyncRecordDetail(
    string Id,
    string Form,
    string State,
    string Label,
    DateTimeOffset ReceivedAt,
    string? SubmittedBy,
    int SyncAttempts,
    DateTimeOffset? LastAttemptAt,
    DateTimeOffset? NextAttemptAt,
    string? Error,
    DuplicateOfSummary? DuplicateOf,
    bool CanRetry,
    bool CanDismiss,
    IReadOnlyList<AdminActionEntry> History);

/// <summary>The answer to a retry or a dismissal: where the record was, and where it is now.</summary>
public record ResolutionResponse(string PreviousState, SyncRecordDetail Record);

/// <summary>Body of <c>POST /api/admin/sync-status/{id}/dismiss</c>.</summary>
/// <param name="Reason">Required: why this record will never be sent. Kept in the audit trail.</param>
public record DismissRequest(string? Reason);
