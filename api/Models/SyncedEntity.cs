namespace api.Models;

/// <summary>
/// The offline-first bookkeeping every synced Room entity carries
/// (id, syncStatus, createdAt, updatedAt).
/// </summary>
public abstract class SyncedEntity
{
    /// <summary>Client-generated UUID string (kept as text so whatever the device sends is stored verbatim).</summary>
    public required string Id { get; set; }

    public SyncStatus SyncStatus { get; set; } = SyncStatus.Pending;

    /// <summary>Epoch milliseconds, exactly as the device sends it (Room: Long).</summary>
    public long CreatedAt { get; set; }

    /// <summary>Epoch milliseconds, exactly as the device sends it (Room: Long).</summary>
    public long UpdatedAt { get; set; }
}

/// <summary>
/// A <see cref="SyncedEntity"/> that this backend forwards to Foodspace. Adds SERVER-ONLY columns
/// (no Room equivalent) so the Admin sync-monitoring and failed-sync tooling (#49, #50) have
/// what they need without bolting fields on later.
/// </summary>
public abstract class ForwardedEntity : SyncedEntity
{
    /// <summary>When this backend received the record. Set by the database (now()).</summary>
    public DateTimeOffset ReceivedAt { get; set; }

    /// <summary>How many times forwarding to Foodspace has been attempted.</summary>
    public int SyncAttempts { get; set; }

    public DateTimeOffset? LastSyncAttemptAt { get; set; }

    /// <summary>Last forwarding error, shown to Admins when SyncStatus is Failed.</summary>
    public string? SyncError { get; set; }
}
