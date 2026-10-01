using api.DTOs;
using api.Models;

namespace api.Services;

/// <summary>
/// Validates one decision of a sync batch against the backend schema (api/Models + AppDbContext). Returns every problem
/// found, so the mobile app and its users get specific messages. The officer is not validated: it is taken from the token.
/// </summary>
public static class VettingDecisionSyncValidator
{
    public const int MaxBatchSize = 100;
    public const int MaxNotesLength = 4000;

    private const int MaxIdLength = 64;
    private const int MaxRecordIdLength = 200;

    public static List<string> Validate(VettingDecisionSyncItemDto d)
    {
        var errors = new List<string>();

        if (string.IsNullOrWhiteSpace(d.Id)) errors.Add("id is required.");
        else if (d.Id.Length > MaxIdLength || !Guid.TryParse(d.Id, out _)) errors.Add("id must be a UUID.");

        if (string.IsNullOrWhiteSpace(d.FoodspaceRecordId)) errors.Add("foodspaceRecordId is required.");
        else if (d.FoodspaceRecordId.Length > MaxRecordIdLength) errors.Add($"foodspaceRecordId is too long (max {MaxRecordIdLength} characters).");

        if (!TryParseOutcome(d.Outcome, out _)) errors.Add("outcome must be APPROVE, REJECT or FLAG.");

        if (d.Notes is { Length: > MaxNotesLength }) errors.Add($"notes is too long (max {MaxNotesLength} characters).");

        if (d.DecisionTimestamp <= 0) errors.Add("decisionTimestamp is required (epoch milliseconds).");
        if (d.CreatedAt <= 0) errors.Add("createdAt is required (epoch milliseconds).");
        if (d.UpdatedAt <= 0) errors.Add("updatedAt is required (epoch milliseconds).");

        return errors;
    }

    /// <summary>The wire values are the enum names in upper case, exactly (what the app and Foodspace use).</summary>
    public static bool TryParseOutcome(string? value, out DecisionOutcome outcome)
    {
        outcome = default;
        return value is "APPROVE" or "REJECT" or "FLAG" && Enum.TryParse(value, ignoreCase: true, out outcome);
    }
}
