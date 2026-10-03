using System.Text.RegularExpressions;
using api.Models;

namespace api.Services;

/// <summary>
/// What a username, a password and a CBO assignment must look like. One definition, used by the first-admin bootstrap and by
/// the Admin endpoints, so an account can never be created by one path that the other would refuse. Each method returns the
/// reason it is not acceptable (safe to show to the Admin; it never contains the password), or null when it is.
/// </summary>
public static partial class AccountRules
{
    public const int MinPasswordLength = 12;
    public const int MaxPasswordLength = 128;
    public const int MaxCboIdLength = 100;

    /// <summary>Lower-case letters, digits, dot, underscore and hyphen; starts with a letter or digit; 3 to 64 characters.</summary>
    [GeneratedRegex("^[a-z0-9][a-z0-9._-]{2,63}$", RegexOptions.CultureInvariant)]
    private static partial Regex UsernamePattern();

    /// <summary>The username as it is stored and looked up: trimmed and lower-case (the same rule as sign-in).</summary>
    public static string NormalizeUsername(string username) => AuthService.Normalize(username);

    public static string? UsernameProblem(string normalizedUsername) =>
        UsernamePattern().IsMatch(normalizedUsername)
            ? null
            : "The username must be 3 to 64 characters: lower-case letters, digits, '.', '_' or '-', starting with a letter or digit.";

    /// <summary>
    /// Length first (a long passphrase beats a short "complex" one), and nothing guessable from the account itself. Spaces are
    /// allowed, so a passphrase works. This is for passwords an Admin sets; sign-in itself accepts whatever was stored.
    /// </summary>
    public static string? PasswordProblem(string normalizedUsername, string? password)
    {
        if (string.IsNullOrEmpty(password)) return "A password is required.";
        if (password.Length < MinPasswordLength) return $"The password must be at least {MinPasswordLength} characters.";
        if (password.Length > MaxPasswordLength) return $"The password can be at most {MaxPasswordLength} characters.";
        if (password.Any(char.IsControl)) return "The password cannot contain control characters.";
        if (string.IsNullOrWhiteSpace(password)) return "The password cannot be only spaces.";
        if (password.Distinct().Count() < 4) return "The password is too repetitive: use at least four different characters.";
        if (normalizedUsername.Length >= 3 && password.Contains(normalizedUsername, StringComparison.OrdinalIgnoreCase))
            return "The password cannot contain the username.";
        return null;
    }

    /// <summary>
    /// A collector must belong to a CBO (the sync endpoint stamps every record with it, so without one their records would be
    /// refused or misfiled); Vetting and Admin users belong to none.
    /// </summary>
    public static string? CboProblem(UserRole role, string? cboId)
    {
        if (role == UserRole.CBO_COLLECTION)
        {
            if (string.IsNullOrWhiteSpace(cboId)) return "A collector needs a cboId: the CBO they collect for.";
            if (cboId.Trim().Length > MaxCboIdLength) return $"The cboId can be at most {MaxCboIdLength} characters.";
            return null;
        }
        return string.IsNullOrWhiteSpace(cboId) ? null : "Only a CBO_COLLECTION user has a cboId.";
    }
}
