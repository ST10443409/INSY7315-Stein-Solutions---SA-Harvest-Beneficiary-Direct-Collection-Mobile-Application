using api.Models;

namespace api.Services.Foodspace;

/// <summary>What Foodspace's `POST /api/external/vetting-decisions` expects (the simulator's VettingDecisionDto).</summary>
public record FoodspaceVettingDecision(
    string Id,
    string FoodspaceRecordId,
    string Outcome,
    string? Notes,
    string OfficerId,
    long DecisionTimestamp,
    long CreatedAt,
    long UpdatedAt);

/// <summary>
/// The one place that maps our VettingDecision to Foodspace's payload. Audit it against Foodspace's contract whenever
/// either schema changes:
///
///   vetting_decisions (ours)      Foodspace field      Notes
///   ----------------------------  -------------------  ---------------------------------------------------
///   Id                            id                   client UUID; Foodspace upserts by it
///   FoodspaceRecordId             foodspaceRecordId    the beneficiary the decision is about
///   Outcome                       outcome              APPROVE | REJECT | FLAG (the enum names, upper case)
///   Notes                         notes                the officer's free text; may be null
///   OfficerId                     officerId            the signed-in officer, taken from their token
///   DecisionTimestamp             decisionTimestamp    epoch ms, as recorded on the device
///   CreatedAt / UpdatedAt         createdAt / updatedAt  epoch ms
///
/// Deliberately NOT sent (ours only): SyncStatus, ForwardingStatus, ReceivedAt, SyncAttempts, LastSyncAttemptAt,
/// NextForwardAttemptAt, SyncError.
/// </summary>
public static class FoodspaceVettingDecisionMapper
{
    public static FoodspaceVettingDecision ToFoodspace(VettingDecision d) => new(
        Id: d.Id,
        FoodspaceRecordId: d.FoodspaceRecordId,
        Outcome: d.Outcome.ToString().ToUpperInvariant(),
        Notes: d.Notes,
        OfficerId: d.OfficerId,
        DecisionTimestamp: d.DecisionTimestamp,
        CreatedAt: d.CreatedAt,
        UpdatedAt: d.UpdatedAt);
}
