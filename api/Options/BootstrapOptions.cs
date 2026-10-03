namespace api.Options;

/// <summary>
/// The first administrator, created once when the API starts, bound from the "Bootstrap" configuration section.
///
/// Why it exists: login accounts are otherwise created only through the Admin endpoints, which need an Admin to sign in. On a
/// fresh deployment nobody can, and the test-user seeder is Development-only on purpose. So the very first admin comes from
/// configuration: <c>Bootstrap__AdminUsername</c> (plain) and <c>Bootstrap__AdminPassword</c> (a SECRET: a Key Vault reference,
/// never a committed value).
///
/// Behaviour (see <see cref="api.Data.AdminBootstrapper"/>): it does nothing once any active admin exists, it never changes an
/// existing account, and it refuses to start on a half-set or unacceptable configuration instead of guessing. After the first
/// sign-in remove the password setting: it has done its job, and a secret that is no longer needed should not stay around.
/// </summary>
public class BootstrapOptions
{
    public const string SectionName = "Bootstrap";

    public string? AdminUsername { get; set; }

    /// <summary>A secret. Must satisfy <see cref="api.Services.AccountRules.PasswordProblem"/>.</summary>
    public string? AdminPassword { get; set; }

    /// <summary>True when either value is set, which is what switches the bootstrap on.</summary>
    public bool IsConfigured => !string.IsNullOrWhiteSpace(AdminUsername) || !string.IsNullOrEmpty(AdminPassword);

    // Never print the secret, even by accident in a log line.
    public override string ToString() => $"BootstrapOptions(AdminUsername={AdminUsername}, AdminPassword={(string.IsNullOrEmpty(AdminPassword) ? "unset" : "set")})";
}
