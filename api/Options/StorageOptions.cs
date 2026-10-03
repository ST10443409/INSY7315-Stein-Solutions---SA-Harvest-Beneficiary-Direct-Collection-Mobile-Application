namespace api.Options;

/// <summary>
/// Where signatures and photos are kept, bound from the "Storage" configuration section.
///
/// In Azure only <see cref="AccountUri"/> is set (an app setting, not a secret): the API reaches the private container with its
/// managed identity (the "Storage Blob Data Contributor" role on that one container), so there is no account key, connection
/// string or SAS token anywhere. <see cref="ConnectionString"/> exists for local development against the Azurite emulator and
/// is refused outside Development (see <see cref="api.Infrastructure.SecurityStartupChecks"/>), because a connection string
/// carries an account key. With neither set the API still starts, and an upload is answered 503 (nothing is lost: the app keeps
/// the file and tries again).
/// </summary>
public class StorageOptions
{
    public const string SectionName = "Storage";

    /// <summary>The storage account's blob endpoint, e.g. <c>https://sahprodsteinstore.blob.core.windows.net/</c>.</summary>
    public string? AccountUri { get; set; }

    /// <summary>The private container the files go in.</summary>
    public string Container { get; set; } = "attachments";

    /// <summary>Development only (Azurite). Carries a key, so never in a real environment.</summary>
    public string? ConnectionString { get; set; }

    public bool IsConfigured => !string.IsNullOrWhiteSpace(AccountUri) || !string.IsNullOrWhiteSpace(ConnectionString);

    // Never print the connection string, even by accident in a log line.
    public override string ToString() =>
        $"StorageOptions(AccountUri={AccountUri}, Container={Container}, ConnectionString={(string.IsNullOrEmpty(ConnectionString) ? "unset" : "set")})";
}
