namespace api.DTOs;

// Wire contract for POST /api/cbo-collection/sync. Field names are the Room field names (camelCase JSON).
// Everything is nullable on purpose: a missing field must become that record's validation error, not a 400
// for the whole batch.

public class CboProductLineSyncDto
{
    public string? Id { get; set; }

    /// <summary>Optional: when sent it must equal the collection's id; when omitted the collection's id is used.</summary>
    public string? CollectionId { get; set; }

    public string? Category { get; set; }

    /// <summary>Numeric value as text, as on the device (e.g. "42.5").</summary>
    public string? Kg { get; set; }

    public string? Notes { get; set; }

    /// <summary>Optional: default to the collection's timestamps.</summary>
    public long? CreatedAt { get; set; }

    public long? UpdatedAt { get; set; }
}

public class CboCollectionSyncItemDto
{
    /// <summary>The client-generated UUID. It is the idempotency key.</summary>
    public string? Id { get; set; }

    public string? CboId { get; set; }
    public string? ArrivalTime { get; set; }
    public string? DepartureTime { get; set; }
    public string? DonorName { get; set; }
    public bool DonorSigned { get; set; }
    public bool CboSigned { get; set; }
    public string? DeliveryNote { get; set; }
    public bool NoteAttached { get; set; }
    public string? CollectNotes { get; set; }
    public List<bool>? Shots { get; set; }
    public double? Latitude { get; set; }
    public double? Longitude { get; set; }
    public long CreatedAt { get; set; }
    public long UpdatedAt { get; set; }
    public List<CboProductLineSyncDto>? ProductLines { get; set; }
}

public class CboCollectionSyncRequest
{
    public List<CboCollectionSyncItemDto>? Records { get; set; }
}

/// <summary>Machine-readable per-record failure codes the mobile app can branch on.</summary>
public static class CboSyncErrorCodes
{
    /// <summary>The record is invalid. Retrying the same data will never work: do not retry.</summary>
    public const string ValidationFailed = "VALIDATION_FAILED";

    /// <summary>The server could not save this record right now. Safe to retry.</summary>
    public const string ServerError = "SERVER_ERROR";

    /// <summary>
    /// A DIFFERENT client id was used for a collection that was already received (same CBO, donor, date and delivery
    /// note). Not an error in the data and not a retry: the submission is kept for Admin review, the original is
    /// untouched. Do not resend. See <see cref="api.Services.CboCollectionIngestionService"/>.
    /// </summary>
    public const string DuplicateDetected = "DUPLICATE_DETECTED";
}

/// <summary>The outcome for one record of the batch.</summary>
/// <param name="ClientId">The record's id as sent (null only if the record had none).</param>
/// <param name="Success">True when the record is stored on the server (including "already stored").</param>
/// <param name="AlreadyReceived">True when the id was already stored, so this was a retry and nothing was written.</param>
/// <param name="Error">Human-readable reason when not successful.</param>
/// <param name="ErrorCode">See <see cref="CboSyncErrorCodes"/>.</param>
/// <param name="Retryable">False for validation failures (permanent), true for transient server errors.</param>
/// <param name="DuplicateOfId">Set with DUPLICATE_DETECTED: the id of the record that was already received.</param>
public record CboCollectionSyncResult(
    string? ClientId,
    bool Success,
    bool AlreadyReceived = false,
    string? Error = null,
    string? ErrorCode = null,
    bool Retryable = false,
    string? DuplicateOfId = null);

public record CboCollectionSyncResponse(IReadOnlyList<CboCollectionSyncResult> Results);
