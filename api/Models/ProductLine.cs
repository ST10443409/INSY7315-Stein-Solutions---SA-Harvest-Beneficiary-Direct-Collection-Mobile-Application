namespace api.Models;

/// <summary>Room: ProductLineEntity (table "product_lines"), a child row of a <see cref="CboCollection"/>.</summary>
public class ProductLine : SyncedEntity
{
    /// <summary>FK to <see cref="CboCollection"/>. Enforced here (Room only does it "in spirit"): a line can't outlive its collection.</summary>
    public required string CollectionId { get; set; }

    public required string Category { get; set; }

    /// <summary>Numeric value held as text, as the mobile requirement specifies (Room: String). Validate on ingestion (#36).</summary>
    public required string Kg { get; set; }

    public string? Notes { get; set; }

    public CboCollection? Collection { get; set; }
}
