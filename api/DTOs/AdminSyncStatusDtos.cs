namespace api.DTOs;

/// <summary>
/// How many records of one form are in each forwarding state. The states do not overlap, so they add up to <see cref="Total"/>.
/// </summary>
/// <param name="Waiting">Received here, not yet sent to Foodspace.</param>
/// <param name="Retrying">Foodspace has not accepted it yet and an automatic retry is scheduled.</param>
/// <param name="NeedsAttention">Foodspace has not accepted it and no retry is scheduled (rejected, or retries ran out). Waits for an Admin (#50).</param>
/// <param name="Forwarded">Foodspace accepted it.</param>
/// <param name="Duplicates">Form 1 only: a suspected duplicate submission, held for Admin review and never sent. Zero for Form 2.</param>
/// <param name="Superseded">Form 2 only: replaced by a newer decision on the same beneficiary, kept as history. Zero for Form 1.</param>
public record FormSyncCounts(
    int Total,
    int Waiting,
    int Retrying,
    int NeedsAttention,
    int Forwarded,
    int Duplicates,
    int Superseded);

/// <summary>Response of <c>GET /api/admin/sync-status</c> (inside the usual envelope's <c>data</c>).</summary>
/// <param name="GeneratedAt">When the counts were taken, so the screen can say "as of 10:42".</param>
public record AdminSyncStatusResponse(FormSyncCounts CboCollections, FormSyncCounts VettingDecisions, DateTimeOffset GeneratedAt);
