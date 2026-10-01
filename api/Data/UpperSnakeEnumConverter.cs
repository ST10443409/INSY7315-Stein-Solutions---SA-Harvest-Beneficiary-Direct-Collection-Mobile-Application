using System.Text.RegularExpressions;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace api.Data;

/// <summary>
/// Stores an enum as its UPPER_SNAKE_CASE name (<c>SyncedLocalPendingFoodspace</c> becomes
/// <c>SYNCED_LOCAL_PENDING_FOODSPACE</c>). For server-only enums whose names have several words.
/// </summary>
public class UpperSnakeEnumConverter<TEnum> : ValueConverter<TEnum, string>
    where TEnum : struct, Enum
{
    public UpperSnakeEnumConverter()
        : base(v => ToWire(v), s => Parse(s))
    {
    }

    private static string ToWire(TEnum value) =>
        Regex.Replace(value.ToString(), "([a-z0-9])([A-Z])", "$1_$2").ToUpperInvariant();

    private static TEnum Parse(string wire) =>
        Enum.GetValues<TEnum>().Single(v => ToWire(v) == wire);
}
