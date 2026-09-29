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
}
