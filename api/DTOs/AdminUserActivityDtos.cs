namespace api.DTOs;

/// <summary>
/// One thing a user did: submitted a Form 1 collection or recorded a Form 2 decision. <see cref="Form"/> is
/// <c>CBO_COLLECTION</c> or <c>VETTING_DECISION</c>; <see cref="Id"/> and <see cref="Form"/> together are the record reference.
/// </summary>
/// <param name="User">The account that did it (from the token when it was sent). Null for a Form 1 record from before submitters were recorded.</param>
/// <param name="Role">That account's role today (<c>CBO_COLLECTION</c>, <c>VETTING</c>, <c>ADMIN</c>), or null if the account no longer exists or there is no user.</param>
/// <param name="At">When the work was done, by the device's clock: a collection's created time, a decision's decision time. Work done offline counts on the day it was done, not the day it synced.</param>
/// <param name="ReceivedAt">When this backend received it.</param>
/// <param name="Label">A short line that identifies the record: the donor, or the beneficiary and outcome.</param>
public record UserActivityItem(
    string Id,
    string Form,
    string? User,
    string? Role,
    DateTimeOffset At,
    DateTimeOffset ReceivedAt,
    string Label);

/// <summary>
/// A page of activity, newest first. <see cref="From"/> and <see cref="To"/> are the dates actually applied
/// (<c>yyyy-MM-dd</c>, South African days, <c>To</c> inclusive), so a caller that sent none can see the default window.
/// Ask for <c>page + 1</c> while <see cref="HasMore"/> is true.
/// </summary>
public record UserActivityResponse(
    IReadOnlyList<UserActivityItem> Items,
    int Page,
    int PageSize,
    int TotalCount,
    bool HasMore,
    string? From,
    string? To);
