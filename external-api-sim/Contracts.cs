namespace external_api_sim;

// The shapes Foodspace's API is expected to send/receive.
//
// Field names deliberately match the Android Room entities and the backend database (camelCase on the wire),
// so a field means the same thing in all three places. Device-only bookkeeping (syncStatus) is not part of
// the contract: Foodspace does not need to know about our offline queue.
//
// Timestamps (createdAt, updatedAt, decisionTimestamp, lastDateFed) are epoch MILLISECONDS.
// Enum-like values are UPPER_CASE strings (outcome: APPROVE | REJECT | FLAG, tone: OK | WARN | NEW).

// ── Pulled from Foodspace ──────────────────────────────────────────────────────────────────

/// <summary>A community-based organisation (CBO) that collects surplus food.</summary>
public record CboDto(string Id, string Name, string Status, string Meta, string Tone);

/// <summary>
/// A beneficiary organisation's application/profile: everything a vetting officer needs for Form 2.
/// Foodspace owns this data; we only ever read it.
/// </summary>
public record BeneficiaryDto
{
    public required string Id { get; init; }

    // Organisation & contact
    public required string LegalName { get; init; }
    public required string ContactName { get; init; }
    public required string ContactEmail { get; init; }
    public required string ContactPhone { get; init; }
    public string? Website { get; init; }

    // Location
    public string? Address { get; init; }
    public string? Address2 { get; init; }
    public required string Province { get; init; }
    public required string What3words { get; init; }

    // Programmes & services
    public required string CoreBusiness { get; init; }
    public List<string> TargetPopulation { get; init; } = new();
    public required string Programmes { get; init; }
    public required string DistributionChannel { get; init; }

    // Staffing
    public int FullTimeFemales { get; init; }
    public int FullTimeMales { get; init; }
    public int Volunteers { get; init; }

    // Registration
    public bool RegisteredNpo { get; init; }
    public string? NpoCertificate { get; init; }
    public bool RegisteredDsd { get; init; }
    public string? PboCertificate { get; init; }

    // Beneficiary demographics
    public List<string> Race { get; init; } = new();
    public List<string> Gender { get; init; } = new();
    public List<string> AgeGroups { get; init; } = new();

    // Feeding operation
    public required string FeedingFrequency { get; init; }
    public int TotalServed { get; init; }
    public int FemalesServed { get; init; }
    public int MalesServed { get; init; }
    public int AfricanServed { get; init; }
    public int ColouredServed { get; init; }
    public int IndianServed { get; init; }
    public int WhiteServed { get; init; }
    public required string RelianceOnSaHarvest { get; init; }
    public required string TransportCapacity { get; init; }
    public List<string> MealsProvided { get; init; } = new();
    public List<string> DaysOfWeek { get; init; } = new();
    public long? LastDateFed { get; init; }

    // Facilities & hygiene
    public List<string> FoodStorage { get; init; } = new();
    public string? KitchenImages { get; init; }
    public bool KitchenCleanliness { get; init; }
    public bool AccessToWater { get; init; }
    public bool Toilets { get; init; }
    public bool PestFree { get; init; }
    public List<string> InfrastructureChecks { get; init; } = new();

    // Access & security
    public bool EaseOfAccess { get; init; }
    public bool ParkingSecurity { get; init; }
    public string? PoliceProximity { get; init; }

    // Capacity notes
    public string? AdditionalComments { get; init; }
    public string? ProposalWriting { get; init; }
    public string? DigitalCapabilities { get; init; }
    public string? FacilityPhotos { get; init; }

    // Documents
    public bool HasSla { get; init; }
    public bool HasConsent { get; init; }
    public bool HasPolicy { get; init; }
    public string? Certificates { get; init; }
}

// ── Pushed to Foodspace ────────────────────────────────────────────────────────────────────

/// <summary>One product line of a collection (what was collected, and how many kg).</summary>
public record ProductLineDto(string Id, string CollectionId, string Category, string Kg, string? Notes, long CreatedAt, long UpdatedAt);

/// <summary>A completed CBO collection (Form 1), sent with its product lines.</summary>
public record CboCollectionDto(
    string Id,
    string CboId,
    string ArrivalTime,
    string? DepartureTime,
    string DonorName,
    bool DonorSigned,
    bool CboSigned,
    string DeliveryNote,
    bool NoteAttached,
    string CollectNotes,
    List<bool> Shots,
    double? Latitude,
    double? Longitude,
    long CreatedAt,
    long UpdatedAt,
    List<ProductLineDto> ProductLines);

/// <summary>A vetting officer's decision on a beneficiary (Form 2). Outcome: APPROVE, REJECT or FLAG.</summary>
public record VettingDecisionDto(
    string Id,
    string FoodspaceRecordId,
    string Outcome,
    string? Notes,
    string OfficerId,
    long DecisionTimestamp,
    long CreatedAt,
    long UpdatedAt);

// ── Legacy ─────────────────────────────────────────────────────────────────────────────────

/// <summary>Generic payload used by the first queue prototype. Superseded by the typed push endpoints.</summary>
public class SyncPayload
{
    public string Id { get; set; } = string.Empty;
    public string Data { get; set; } = string.Empty;
}
