using api.Data;
using api.Models;
using Microsoft.EntityFrameworkCore;

namespace api.Services.Foodspace;

/// <summary>
/// Makes sure only one worker sends a given record to Foodspace at a time.
///
/// Without it, anything that runs the forwarder twice at once sends the record twice: a second instance of the API, the
/// old and new container while a deployment rolls over, or (even on one instance) the call right after ingestion racing
/// the background loop. Foodspace may or may not de-duplicate (docs/OPEN-DECISIONS.md, #1), so we do not rely on it.
///
/// How it works: a worker writes a fresh <see cref="ForwardedEntity.ForwardClaimId"/> and a lease expiry to the row before
/// it sends. The claim id is a concurrency token, so that write is "update ... where claim id is still what I read": if
/// another worker claimed the row between my read and my write, mine changes no row, EF reports a concurrency conflict, and
/// I leave the record alone. The database decides the winner, so this holds across processes and machines, which an
/// in-process lock would not. The lease lets a record whose worker died be taken over instead of stuck for ever.
///
/// The claim id changes on EVERY claim and EVERY release and never goes back to an earlier value. Releasing by setting it
/// back to null would be wrong: a worker that read the row before the first claim still expects null, so once the first
/// worker had sent the record and released it, that stale worker's claim would succeed and the record would be sent twice
/// (the "ABA" trap; <c>ForwardingClaimTests.TwoForwardersRunningAtOnce_*</c> exist to catch it). Whether a record is free is
/// therefore told by <see cref="ForwardedEntity.ForwardClaimedUntil"/> alone: empty or in the past means free.
/// </summary>
public static class ForwardingClaim
{
    /// <summary>
    /// Tries to claim <paramref name="record"/> for one send. True means this caller now owns it and must call
    /// <see cref="ReleaseAsync"/> (which saves the outcome too); false means somebody else is sending it (or just did), and the
    /// record has been detached so nothing this caller changed on it is saved.
    /// </summary>
    public static async Task<bool> TryClaimAsync(
        AppDbContext db, ForwardedEntity record, DateTimeOffset now, TimeSpan lease, CancellationToken cancellationToken)
    {
        // A live claim held by someone else (its lease has not run out): do not even try.
        if (record.ForwardClaimedUntil is { } until && until > now)
        {
            db.Entry(record).State = EntityState.Detached;
            return false;
        }

        record.ForwardClaimId = Guid.NewGuid();
        record.ForwardClaimedUntil = now + lease;
        try
        {
            // Saves anything else the caller already changed on the record too (for example an Admin's attempt reset), which
            // is what we want when the claim succeeds and is thrown away by the detach below when it does not.
            await db.SaveChangesAsync(cancellationToken);
            return true;
        }
        catch (DbUpdateConcurrencyException)
        {
            db.Entry(record).State = EntityState.Detached;
            return false;
        }
    }

    /// <summary>
    /// Ends the claim and saves the outcome the caller has already put on the record, in one write. Returns false when the
    /// claim was lost in the meantime (the lease ran out while Foodspace was slow and another worker took the record over):
    /// the record is then left as that worker has it and the caller's outcome is dropped.
    /// </summary>
    public static async Task<bool> ReleaseAsync(AppDbContext db, ForwardedEntity record, CancellationToken cancellationToken)
    {
        record.ForwardClaimId = Guid.NewGuid(); // a new value, never null: see the class comment
        record.ForwardClaimedUntil = null;
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return true;
        }
        catch (DbUpdateConcurrencyException)
        {
            db.Entry(record).State = EntityState.Detached;
            return false;
        }
    }

    /// <summary>The lease: long enough for one Foodspace call (its timeout) plus the database writes around it, with room to spare.</summary>
    public static TimeSpan LeaseFor(FoodspaceOptions options) => TimeSpan.FromSeconds(Math.Max(1, options.ClaimLeaseSeconds));
}
