namespace api.DTOs;

// Wire contract for POST /api/vetting/sync: the same shape as POST /api/cbo-collection/sync, applied to vetting
// decisions. Field names are the Room field names of VettingDecision (camelCase JSON). Everything is nullable on
// purpose: a missing field must become that record's validation error, not a 400 for the whole batch.
// Device-only fields the app also sends (syncStatus) are ignored.

public class VettingDecisionSyncItemDto
{
    /// <summary>The client-generated UUID. It is the idempotency key.</summary>
    public string? Id { get; set; }

    /// <summary>The Foodspace beneficiary the decision is about.</summary>
    public string? FoodspaceRecordId { get; set; }

    /// <summary>APPROVE, REJECT or FLAG.</summary>
    public string? Outcome { get; set; }

    public string? Notes { get; set; }

    /// <summary>
    /// What the device thinks the officer's username is. Not trusted: the server stores the signed-in user's name from
    /// the token instead, so an officer cannot record a decision as somebody else.
    /// </summary>
    public string? OfficerId { get; set; }

    public long DecisionTimestamp { get; set; }
    public long CreatedAt { get; set; }
    public long UpdatedAt { get; set; }
}

public class VettingDecisionSyncRequest
{
    public List<VettingDecisionSyncItemDto>? Records { get; set; }
}

/// <summary>
/// The outcome for one decision of the batch. Same fields and meaning as <see cref="CboCollectionSyncResult"/> (without
/// the duplicate pointer, which decisions do not have), so the app reads both with the same code. Error codes are the
/// ones in <see cref="CboSyncErrorCodes"/> (VALIDATION_FAILED is permanent, SERVER_ERROR is retryable).
/// </summary>
public record VettingSyncResult(
    string? ClientId,
    bool Success,
    bool AlreadyReceived = false,
    string? Error = null,
    string? ErrorCode = null,
    bool Retryable = false);

public record VettingSyncResponse(IReadOnlyList<VettingSyncResult> Results);
