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

    /// <summary>Where the record stands with Foodspace. See <see cref="Models.ForwardingStatus"/>.</summary>
    public ForwardingStatus ForwardingStatus { get; set; } = ForwardingStatus.Pending;

    /// <summary>When the next automatic forwarding retry is due; null when none is scheduled (retries exhausted, or not retryable).</summary>
    public DateTimeOffset? NextForwardAttemptAt { get; set; }

    /// <summary>How many times forwarding to Foodspace has been attempted.</summary>
    public int SyncAttempts { get; set; }

    public DateTimeOffset? LastSyncAttemptAt { get; set; }

    /// <summary>Last forwarding error (never contains record data), shown to Admins while the record is not Forwarded.</summary>
    public string? SyncError { get; set; }

    /// <summary>
    /// A random value that changes every time a worker claims this record for sending and again when it lets go; null only
    /// for a record nobody has touched yet. It is a concurrency token: every update of the row is conditional on it being
    /// unchanged, so when two workers (a second instance, an overlapping deployment, the worker and an Admin's retry) both
    /// read the row and both try to claim it, the database lets exactly one through and the other gets a
    /// <see cref="Microsoft.EntityFrameworkCore.DbUpdateConcurrencyException"/> and leaves the record alone. It does NOT say
    /// whether the record is busy (that is <see cref="ForwardClaimedUntil"/>); it only has to never repeat a value.
    /// See <see cref="api.Services.Foodspace.ForwardingClaim"/>.
    /// </summary>
    public Guid? ForwardClaimId { get; set; }

    /// <summary>
    /// While a worker is sending this record, when its claim lapses; null when nobody is sending it. A worker that dies
    /// mid-send leaves this behind; once the time has passed another worker may take the record over, so a crash delays a
    /// record but never strands it.
    /// </summary>
    public DateTimeOffset? ForwardClaimedUntil { get; set; }
}
