using System.Security.Cryptography;
using System.Text;
using api.DTOs;

namespace api.Services;

/// <summary>
/// The matching rule that decides whether two submissions describe the SAME real-world collection (#38).
/// This is a product decision, so it lives in this one small class: change <see cref="For"/> and the
/// <c>duplicate_key</c> of future records changes with it.
///
/// Current rule: same CBO + same donor name + same collection date + same delivery note number.
///   * Case, surrounding and repeated whitespace are ignored ("Fresh  Fields" == "fresh fields").
///   * The date is the calendar day of <c>createdAt</c> in South Africa (UTC+2, no daylight saving).
///   * A blank delivery note matches another blank one, so two un-numbered visits to one donor on one day are
///     flagged. That is a deliberate false positive: flagged records only wait for a human look, nothing is dropped.
/// A different delivery note number (a genuine second pick-up the same day) is NOT a duplicate.
///
/// The key is a SHA-256 hash, so the unique index holds a fixed-size value and no donor name.
/// </summary>
public static class CboCollectionDuplicateKey
{
    private static readonly TimeSpan SouthAfrica = TimeSpan.FromHours(2);

    public static string For(CboCollectionSyncItemDto r)
    {
        var date = DateTimeOffset.FromUnixTimeMilliseconds(r.CreatedAt).ToOffset(SouthAfrica).ToString("yyyy-MM-dd");
        var composite = string.Join('|', Normalize(r.CboId), Normalize(r.DonorName), date, Normalize(r.DeliveryNote));
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(composite))).ToLowerInvariant();
    }

    private static string Normalize(string? value) =>
        string.Join(' ', (value ?? string.Empty).Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            .ToLowerInvariant();
}
