using api.Models;

namespace api.DTOs;

/// <summary>
/// Response of <c>GET /api/vetting/records</c> (inside the usual envelope's <c>data</c>).
/// <see cref="Items"/> carry exactly the fields of the Android <c>FoodspaceBeneficiaryRecord</c> entity, nothing more.
/// </summary>
/// <param name="HasMore">True when another page follows; ask for <c>page + 1</c>.</param>
/// <param name="FetchedAt">When the backend last got the list from Foodspace (the app can show "updated 10 min ago").</param>
/// <param name="Stale">
/// True when the list is older than the refresh interval because Foodspace could not be reached. The records are still the
/// latest we have, so the app may use them; it is a hint to show "may be out of date", not an error.
/// </param>
public record VettingRecordsResponse(
    IReadOnlyList<FoodspaceBeneficiaryRecord> Items,
    int Page,
    int PageSize,
    int TotalCount,
    bool HasMore,
    DateTimeOffset FetchedAt,
    bool Stale);
