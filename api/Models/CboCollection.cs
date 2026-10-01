namespace api.Models;

/// <summary>Room: CboCollectionEntity (table "cbo_collections"), the Form 1 record.</summary>
public class CboCollection : ForwardedEntity
{
    /// <summary>Which CBO collected. No FK: mirrors Room, and a collection must never be rejected because the CBO list hasn't been pulled yet.</summary>
    public required string CboId { get; set; }

    // Collection record fields
    // NB: arrival/departure are display strings on the device ("10:00 AM"), kept as-is for parity.
    public required string ArrivalTime { get; set; }
    public string? DepartureTime { get; set; }
    public required string DonorName { get; set; }
    public bool DonorSigned { get; set; }
    public bool CboSigned { get; set; }
    public required string DeliveryNote { get; set; }
    public bool NoteAttached { get; set; }
    public required string CollectNotes { get; set; }

    /// <summary>Room stores "true,false,..." text; here a native boolean[]. Same logical value, JSON is an array.</summary>
    public List<bool> Shots { get; set; } = new();

    // Location / GPS capture
    public double? Latitude { get; set; }
    public double? Longitude { get; set; }

    // Server-only: who submitted it (username from the JWT once #30 lands). For Admin activity oversight (#51).
    public string? SubmittedBy { get; set; }

    /// <summary>
    /// SERVER-ONLY. Fingerprint of the real-world collection (CBO + donor + collection date + delivery note), computed by
    /// <see cref="api.Services.CboCollectionDuplicateKey"/>. Distinct from <see cref="SyncedEntity.Id"/>: two different
    /// client ids can describe the same visit. A partial unique index allows only ONE original per key.
    /// </summary>
    public string? DuplicateKey { get; set; }

    /// <summary>
    /// SERVER-ONLY. Null for an original. For a suspected duplicate submission (different client id, same
    /// <see cref="DuplicateKey"/>) the id of the original it matched. The duplicate is kept, untouched, for Admin
    /// review (#49/#50), is never merged into the original, and is not forwarded to Foodspace.
    /// </summary>
    public string? DuplicateOfId { get; set; }

    public List<ProductLine> ProductLines { get; set; } = new();
}
