using api.Options;
using api.Services.Foodspace;
using Npgsql;

namespace api.Infrastructure;

/// <summary>
/// Configuration mistakes that would quietly weaken a real deployment (#54). The app refuses to start while any are
/// present, naming every one, rather than running half-protected:
/// - test accounts with a shared, documented password outside Development;
/// - with HTTPS required, the onward hops in plain text: the Foodspace API key and beneficiary data to Foodspace, and
///   everything to the database. "HTTPS end to end" includes the hops the phone never sees.
/// </summary>
public static class SecurityStartupChecks
{
    public static IReadOnlyList<string> FindProblems(
        IConfiguration configuration, IHostEnvironment environment, SecurityOptions security, FoodspaceOptions foodspace)
    {
        var problems = new List<string>();

        if (configuration.GetValue<bool>("Seed:Enabled") && !environment.IsDevelopment())
        {
            problems.Add("Seed:Enabled creates test accounts (including an Admin) that share one password; it is only allowed " +
                         $"in Development, not '{environment.EnvironmentName}'.");
        }

        // A storage connection string carries an account key. In a real environment the API uses its managed identity instead.
        if (!string.IsNullOrWhiteSpace(configuration["Storage:ConnectionString"]) && !environment.IsDevelopment())
        {
            problems.Add("Storage:ConnectionString carries a storage account key; it is only allowed in Development (Azurite). " +
                         "Use Storage:AccountUri with the app's managed identity instead.");
        }

        if (!security.RequireHttps) return problems;

        if (!string.IsNullOrWhiteSpace(configuration["Storage:AccountUri"]) &&
            !(Uri.TryCreate(configuration["Storage:AccountUri"], UriKind.Absolute, out var storageUri) && storageUri.Scheme == Uri.UriSchemeHttps))
        {
            problems.Add("Storage:AccountUri must be an https:// address while Security:RequireHttps is on: signatures and photos " +
                         "must not cross the network in plain text.");
        }

        if (!Uri.TryCreate(foodspace.BaseUrl, UriKind.Absolute, out var foodspaceUrl) || foodspaceUrl.Scheme != Uri.UriSchemeHttps)
        {
            problems.Add("Foodspace:BaseUrl (or ExternalApi:BaseUrl) must be an https:// address while Security:RequireHttps is on: " +
                         "the Foodspace API key and beneficiary data must not cross the network in plain text.");
        }

        if (!DatabaseConnectionIsEncrypted(configuration.GetConnectionString("Default")))
        {
            problems.Add("ConnectionStrings:Default must set 'SSL Mode=VerifyFull' (or Require / VerifyCA) while Security:RequireHttps " +
                         "is on, unless the database is on this host.");
        }

        return problems;
    }

    private static bool DatabaseConnectionIsEncrypted(string? connectionString)
    {
        if (string.IsNullOrWhiteSpace(connectionString)) return true; // reported by the DbContext set-up instead
        NpgsqlConnectionStringBuilder builder;
        try
        {
            builder = new NpgsqlConnectionStringBuilder(connectionString);
        }
        catch (ArgumentException)
        {
            return false;
        }

        if (builder.SslMode is SslMode.Require or SslMode.VerifyCA or SslMode.VerifyFull) return true;

        // A database on this host (or a Unix socket) never crosses a network.
        var hosts = (builder.Host ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        return hosts.Length > 0 && hosts.All(h => h.StartsWith('/') || h is "localhost" or "127.0.0.1" or "::1");
    }
}
