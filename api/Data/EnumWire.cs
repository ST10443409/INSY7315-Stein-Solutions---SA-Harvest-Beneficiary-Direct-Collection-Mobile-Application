using System.Text.RegularExpressions;

namespace api.Data;

/// <summary>The UPPER_SNAKE_CASE name of an enum value (<c>NeedsAttention</c> becomes <c>NEEDS_ATTENTION</c>): its wire and database form.</summary>
public static class EnumWire
{
    public static string Of<TEnum>(TEnum value) where TEnum : struct, Enum =>
        Regex.Replace(value.ToString(), "([a-z0-9])([A-Z])", "$1_$2").ToUpperInvariant();
}
