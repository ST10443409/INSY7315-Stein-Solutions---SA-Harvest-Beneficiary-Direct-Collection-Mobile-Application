using api.Models;

namespace api.Services.Foodspace;

/// <summary>What Foodspace's `POST /api/external/cbo-collections` expects for one product line.</summary>
public record FoodspaceProductLine(
    string Id, string CollectionId, string Category, string Kg, string? Notes, long CreatedAt, long UpdatedAt);

/// <summary>What Foodspace's `POST /api/external/cbo-collections` expects (the simulator's CboCollectionDto).</summary>
public record FoodspaceCboCollection(
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
    List<FoodspaceProductLine> ProductLines);

/// <summary>
/// The one place that maps our CboCollection to Foodspace's payload. Audit it against Foodspace's contract
/// whenever either schema changes:
///
///   cbo_collections / product_lines (ours)   Foodspace field            Notes
///   ---------------------------------------  -------------------------  -------------------------------------
///   Id                                       id                         client UUID; Foodspace upserts by it
///   CboId                                    cboId
///   ArrivalTime / DepartureTime              arrivalTime / departureTime
///   DonorName                                donorName
///   DonorSigned / CboSigned                  donorSigned / cboSigned
///   DeliveryNote / NoteAttached              deliveryNote / noteAttached
///   CollectNotes                             collectNotes
///   Shots                                    shots
///   Latitude / Longitude                     latitude / longitude
///   CreatedAt / UpdatedAt                    createdAt / updatedAt      epoch ms, as sent by the device
///   ProductLines[].Id, CollectionId,         productLines[].id, ...     Kg stays text, as on the device
///     Category, Kg, Notes, CreatedAt, UpdatedAt
///
/// Deliberately NOT sent (ours only): SyncStatus, ForwardingStatus, ReceivedAt, SyncAttempts,
/// LastSyncAttemptAt, NextForwardAttemptAt, SyncError, SubmittedBy.
/// </summary>
public static class FoodspaceCboCollectionMapper
{
    public static FoodspaceCboCollection ToFoodspace(CboCollection c) => new(
        Id: c.Id,
        CboId: c.CboId,
        ArrivalTime: c.ArrivalTime,
        DepartureTime: c.DepartureTime,
        DonorName: c.DonorName,
        DonorSigned: c.DonorSigned,
        CboSigned: c.CboSigned,
        DeliveryNote: c.DeliveryNote,
        NoteAttached: c.NoteAttached,
        CollectNotes: c.CollectNotes,
        Shots: c.Shots.ToList(),
        Latitude: c.Latitude,
        Longitude: c.Longitude,
        CreatedAt: c.CreatedAt,
        UpdatedAt: c.UpdatedAt,
        ProductLines: c.ProductLines
            .Select(p => new FoodspaceProductLine(p.Id, p.CollectionId, p.Category, p.Kg, p.Notes, p.CreatedAt, p.UpdatedAt))
            .ToList());
}
