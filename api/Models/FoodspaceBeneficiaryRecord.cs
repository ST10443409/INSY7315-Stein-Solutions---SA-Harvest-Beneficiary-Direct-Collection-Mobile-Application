namespace api.Models;

/// <summary>
/// Room: FoodspaceBeneficiaryRecord (table "foodspace_beneficiary_records").
/// A read-only cache of the Foodspace beneficiary fields a vetting officer needs for Form 2.
/// Foodspace owns this data; it is fetched through their API and replaced wholesale, never edited here.
/// </summary>
public class FoodspaceBeneficiaryRecord
{
    /// <summary>Foodspace's own record ID (text, not assumed to be a UUID).</summary>
    public required string Id { get; set; }

    // Organisation & contact
    public required string LegalName { get; set; }
    public required string ContactName { get; set; }
    public required string ContactEmail { get; set; }
    public required string ContactPhone { get; set; }
    public string? Website { get; set; }

    // Location
    public string? Address { get; set; }
    public string? Address2 { get; set; }
    public required string Province { get; set; }
    public required string What3words { get; set; }

    // Programmes & services
    public required string CoreBusiness { get; set; }
    public List<string> TargetPopulation { get; set; } = new();
    public required string Programmes { get; set; }
    public required string DistributionChannel { get; set; }

    // Staffing
    public int FullTimeFemales { get; set; }
    public int FullTimeMales { get; set; }
    public int Volunteers { get; set; }

    // Registration
    public bool RegisteredNpo { get; set; }
    public string? NpoCertificate { get; set; }
    public bool RegisteredDsd { get; set; }
    public string? PboCertificate { get; set; }

    // Beneficiary demographics
    public List<string> Race { get; set; } = new();
    public List<string> Gender { get; set; } = new();
    public List<string> AgeGroups { get; set; } = new();

    // Feeding operation
    public required string FeedingFrequency { get; set; }
    public int TotalServed { get; set; }
    public int FemalesServed { get; set; }
    public int MalesServed { get; set; }
    public int AfricanServed { get; set; }
    public int ColouredServed { get; set; }
    public int IndianServed { get; set; }
    public int WhiteServed { get; set; }
    public required string RelianceOnSaHarvest { get; set; }
    public required string TransportCapacity { get; set; }
    public List<string> MealsProvided { get; set; } = new();
    public List<string> DaysOfWeek { get; set; } = new();

    /// <summary>Epoch milliseconds (Room: Long?).</summary>
    public long? LastDateFed { get; set; }

    // Facilities & hygiene
    public List<string> FoodStorage { get; set; } = new();
    public string? KitchenImages { get; set; }
    public bool KitchenCleanliness { get; set; }
    public bool AccessToWater { get; set; }
    public bool Toilets { get; set; }
    public bool PestFree { get; set; }
    public List<string> InfrastructureChecks { get; set; } = new();

    // Access & security
    public bool EaseOfAccess { get; set; }
    public bool ParkingSecurity { get; set; }
    public string? PoliceProximity { get; set; }

    // Capacity notes
    public string? AdditionalComments { get; set; }
    public string? ProposalWriting { get; set; }
    public string? DigitalCapabilities { get; set; }
    public string? FacilityPhotos { get; set; }

    // Documents
    public bool HasSla { get; set; }
    public bool HasConsent { get; set; }
    public bool HasPolicy { get; set; }
    public string? Certificates { get; set; }

    /// <summary>Server-only: when this row was last fetched from Foodspace. Set by the database (now()).</summary>
    public DateTimeOffset FetchedAt { get; set; }
}
