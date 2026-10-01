using System.Text.RegularExpressions;
using api.DTOs;

namespace api.Services;

/// <summary>
/// Validates one record of a sync batch against the backend schema (api/Models + AppDbContext).
/// Returns every problem found, so the mobile app and its users get specific messages.
/// </summary>
public static class CboCollectionSyncValidator
{
    public const int MaxBatchSize = 100;

    private const int MaxIdLength = 64;
    private const int MaxShortText = 200;
    private const int MaxLongText = 4000;
    private const int MaxProductLines = 200;

    private static readonly Regex Kg = new(@"^\d{1,6}([.]\d{1,3})?$", RegexOptions.Compiled);

    public static List<string> Validate(CboCollectionSyncItemDto r)
    {
        var errors = new List<string>();

        if (string.IsNullOrWhiteSpace(r.Id)) errors.Add("id is required.");
        else if (r.Id.Length > MaxIdLength || !Guid.TryParse(r.Id, out _)) errors.Add("id must be a UUID.");

        Required(errors, "cboId", r.CboId, MaxShortText);
        Required(errors, "arrivalTime", r.ArrivalTime, 32);
        Required(errors, "donorName", r.DonorName, MaxShortText);

        if (r.DepartureTime is { Length: > 32 }) errors.Add("departureTime is too long.");
        if (r.DeliveryNote is { Length: > MaxShortText }) errors.Add("deliveryNote is too long.");
        if (r.CollectNotes is { Length: > MaxLongText }) errors.Add("collectNotes is too long.");

        if (r.Shots is null) errors.Add("shots is required.");
        if (r.Latitude is < -90 or > 90) errors.Add("latitude must be between -90 and 90.");
        if (r.Longitude is < -180 or > 180) errors.Add("longitude must be between -180 and 180.");
        if (r.CreatedAt <= 0) errors.Add("createdAt is required (epoch milliseconds).");
        if (r.UpdatedAt <= 0) errors.Add("updatedAt is required (epoch milliseconds).");

        var lines = r.ProductLines;
        if (lines is null || lines.Count == 0)
        {
            errors.Add("At least one product line is required.");
        }
        else if (lines.Count > MaxProductLines)
        {
            errors.Add($"At most {MaxProductLines} product lines are allowed.");
        }
        else
        {
            var seen = new HashSet<string>();
            for (var i = 0; i < lines.Count; i++)
            {
                var line = lines[i];
                var at = $"productLines[{i}]";
                if (line is null) { errors.Add($"{at} is missing."); continue; }

                if (string.IsNullOrWhiteSpace(line.Id)) errors.Add($"{at}.id is required.");
                else if (line.Id.Length > MaxIdLength || !Guid.TryParse(line.Id, out _)) errors.Add($"{at}.id must be a UUID.");
                else if (!seen.Add(line.Id)) errors.Add($"{at}.id is repeated within the record.");

                if (!string.IsNullOrWhiteSpace(line.CollectionId) && line.CollectionId != r.Id)
                    errors.Add($"{at}.collectionId does not match the record id.");

                Required(errors, $"{at}.category", line.Category, MaxShortText);

                if (string.IsNullOrWhiteSpace(line.Kg)) errors.Add($"{at}.kg is required.");
                else if (!Kg.IsMatch(line.Kg.Trim()) || !(decimal.Parse(line.Kg.Trim(), System.Globalization.CultureInfo.InvariantCulture) > 0))
                    errors.Add($"{at}.kg must be a number greater than 0 (up to 3 decimals).");

                if (line.Notes is { Length: > MaxLongText }) errors.Add($"{at}.notes is too long.");
            }
        }

        return errors;
    }

    private static void Required(List<string> errors, string field, string? value, int maxLength)
    {
        if (string.IsNullOrWhiteSpace(value)) errors.Add($"{field} is required.");
        else if (value.Length > maxLength) errors.Add($"{field} is too long (max {maxLength} characters).");
    }
}
