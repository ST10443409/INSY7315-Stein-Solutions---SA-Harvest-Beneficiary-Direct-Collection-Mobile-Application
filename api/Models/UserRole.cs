namespace api.Models;

/// <summary>
/// The role a user holds. The names are the wire values and MUST match the Android enum in
/// client/.../auth/UserRole.kt verbatim (the mobile app routes on them), which is why they are
/// UPPER_SNAKE_CASE instead of C# style. RoomParityTests fails if the two drift apart.
/// </summary>
public enum UserRole
{
    CBO_COLLECTION,
    VETTING,
    ADMIN
}

/// <summary>The same values as constants, for use in <c>[Authorize(Roles = AppRoles.Admin)]</c>.</summary>
public static class AppRoles
{
    public const string CboCollection = nameof(UserRole.CBO_COLLECTION);
    public const string Vetting = nameof(UserRole.VETTING);
    public const string Admin = nameof(UserRole.ADMIN);
}
