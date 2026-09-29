using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace api.Data;

/// <summary>
/// Stores an enum as its UPPER_CASE name ("PENDING", "APPROVE", ...), which is exactly what the
/// Room type converters write on the device, so the two sides never disagree on enum values.
/// </summary>
public class UpperCaseEnumConverter<TEnum> : ValueConverter<TEnum, string>
    where TEnum : struct, Enum
{
    public UpperCaseEnumConverter()
        : base(v => v.ToString().ToUpperInvariant(), s => Enum.Parse<TEnum>(s, true))
    {
    }
}
