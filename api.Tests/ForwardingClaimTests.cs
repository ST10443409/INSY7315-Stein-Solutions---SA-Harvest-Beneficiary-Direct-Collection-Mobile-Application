using api.Data;
using api.Models;
using api.Services;
using api.Services.Foodspace;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.EntityFrameworkCore.Storage;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace api.Tests;

/// <summary>
/// Two workers must never send the same record to Foodspace (a second instance, an overlapping deployment, an Admin's retry
/// racing the background loop). Every test here uses several DbContexts over ONE in-memory store, which is what separate
/// processes sharing one database look like. See <see cref="ForwardingClaim"/>.
/// </summary>
public class ForwardingClaimTests
{
    internal sealed class ManualTime : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = new(2026, 10, 3, 8, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => Now;
    }

    internal sealed class CountingClient : IFoodspaceApiClient
    {
        private int _sent;
        public int Sent => _sent;
        public FoodspaceResult Answer { get; set; } = FoodspaceResult.Ok;
        /// <summary>Runs in the middle of a send, to play another worker's move while this one waits for Foodspace.</summary>
        public Func<Task>? DuringSend { get; set; }
        public TimeSpan Delay { get; set; } = TimeSpan.Zero;

        private async Task<FoodspaceResult> SendAsync()
        {
            Interlocked.Increment(ref _sent);
            if (DuringSend is not null) await DuringSend();
            if (Delay > TimeSpan.Zero) await Task.Delay(Delay);
            return Answer;
        }

        public Task<FoodspaceResult> SubmitCboCollectionAsync(CboCollection collection, CancellationToken ct = default) => SendAsync();
        public Task<FoodspaceResult> SubmitVettingDecisionAsync(VettingDecision decision, CancellationToken ct = default) => SendAsync();
        public Task<FoodspaceBeneficiariesResult> GetBeneficiariesAsync(CancellationToken ct = default) => throw new NotSupportedException();
    }

    /// <summary>Runs <paramref name="action"/> once, just before the first save that goes through this context.</summary>
    private sealed class BeforeFirstSave(Func<Task> action) : SaveChangesInterceptor
    {
        private bool _done;

        public override async ValueTask<InterceptionResult<int>> SavingChangesAsync(
            DbContextEventData eventData, InterceptionResult<int> result, CancellationToken cancellationToken = default)
        {
            if (!_done)
            {
                _done = true;
                await action();
            }
            return result;
        }
    }

    private static readonly TimeSpan Lease = TimeSpan.FromSeconds(120);

    private readonly InMemoryDatabaseRoot _root = new();
    private readonly string _store = Guid.NewGuid().ToString();
    private readonly ManualTime _time = new();
    private readonly CountingClient _client = new();
    private readonly IOptions<FoodspaceOptions> _options =
        Microsoft.Extensions.Options.Options.Create(new FoodspaceOptions { BaseUrl = "http://foodspace.test", MaxAttempts = 3, BaseDelaySeconds = 30, ClaimLeaseSeconds = 120 });

    /// <summary>A fresh context on the shared store: one "process".</summary>
    private AppDbContext NewDb(params IInterceptor[] interceptors) => new(
        new DbContextOptionsBuilder<AppDbContext>().UseInMemoryDatabase(_store, _root).AddInterceptors(interceptors).Options);

    private CboCollectionForwarder Collections(AppDbContext db) => new(db, _client, _options, _time, NullLogger<CboCollectionForwarder>.Instance);
    private VettingDecisionForwarder Decisions(AppDbContext db) => new(db, _client, _options, _time, NullLogger<VettingDecisionForwarder>.Instance);

    private static CboCollection Collection(string id, DateTimeOffset? claimedUntil = null) => new()
    {
        Id = id, CboId = "cbo-1", ArrivalTime = "10:00", DonorName = "Donor " + id, DeliveryNote = "DN-" + id, CollectNotes = "",
        ForwardClaimId = claimedUntil is null ? null : Guid.NewGuid(), ForwardClaimedUntil = claimedUntil,
    };

    private static VettingDecision Decision(string id, string record = "rec-1", long at = 1, DateTimeOffset? claimedUntil = null) => new()
    {
        Id = id, FoodspaceRecordId = record, OfficerId = "officer", DecisionTimestamp = at, CreatedAt = at,
        ForwardClaimId = claimedUntil is null ? null : Guid.NewGuid(), ForwardClaimedUntil = claimedUntil,
    };

    private async Task SeedAsync(params ForwardedEntity[] records)
    {
        await using var db = NewDb();
        foreach (var r in records)
        {
            if (r is CboCollection c) db.CboCollections.Add(c);
            else db.VettingDecisions.Add((VettingDecision)r);
        }
        await db.SaveChangesAsync();
    }

    // ── the claim itself ───────────────────────────────────────────────────────────

    [Fact]
    public async Task TwoWorkersThatReadTheSameRow_ExactlyOneWinsTheClaim()
    {
        await SeedAsync(Collection("c1"));
        await using var workerA = NewDb();
        await using var workerB = NewDb();
        var seenByA = await workerA.CboCollections.SingleAsync();
        var seenByB = await workerB.CboCollections.SingleAsync(); // both read it before either claimed it

        var a = await ForwardingClaim.TryClaimAsync(workerA, seenByA, _time.Now, Lease, default);
        var b = await ForwardingClaim.TryClaimAsync(workerB, seenByB, _time.Now, Lease, default);

        Assert.True(a);
        Assert.False(b);
        await using var check = NewDb();
        var saved = await check.CboCollections.SingleAsync();
        Assert.Equal(seenByA.ForwardClaimId, saved.ForwardClaimId); // the winner's claim, not overwritten by the loser
    }

    [Fact]
    public async Task AWorkerWithAStaleRead_CannotClaimAfterTheRecordWasSentAndReleased()
    {
        // The trap: B read the row before anyone claimed it. A then claims, sends and releases. If releasing put the claim id
        // back to what B still expects (null), B's claim would succeed and the record would be sent a second time.
        await SeedAsync(Collection("c1"));
        await using var workerA = NewDb();
        await using var workerB = NewDb();
        var seenByA = await workerA.CboCollections.SingleAsync();
        var seenByB = await workerB.CboCollections.SingleAsync();

        Assert.True(await ForwardingClaim.TryClaimAsync(workerA, seenByA, _time.Now, Lease, default));
        Assert.True(await ForwardingClaim.ReleaseAsync(workerA, seenByA, default));

        Assert.False(await ForwardingClaim.TryClaimAsync(workerB, seenByB, _time.Now, Lease, default));
    }

    [Fact]
    public async Task ALoserThatLostTheClaim_DoesNotSaveWhatItHadChanged()
    {
        await SeedAsync(Collection("c1"));
        await using var workerA = NewDb();
        await using var workerB = NewDb();
        var seenByA = await workerA.CboCollections.SingleAsync();
        var seenByB = await workerB.CboCollections.SingleAsync();
        await ForwardingClaim.TryClaimAsync(workerA, seenByA, _time.Now, Lease, default);

        seenByB.SyncAttempts = 0;
        seenByB.SyncError = "should not be saved";
        Assert.False(await ForwardingClaim.TryClaimAsync(workerB, seenByB, _time.Now, Lease, default));

        await using var check = NewDb();
        Assert.Null((await check.CboCollections.SingleAsync()).SyncError);
    }

    [Fact]
    public async Task ARecordWhoseClaimHasBeenReleased_CanBeClaimedAgain()
    {
        await SeedAsync(Collection("c1"));
        await using var workerA = NewDb();
        var record = await workerA.CboCollections.SingleAsync();
        Assert.True(await ForwardingClaim.TryClaimAsync(workerA, record, _time.Now, Lease, default));
        Assert.True(await ForwardingClaim.ReleaseAsync(workerA, record, default));

        await using var workerB = NewDb();
        var again = await workerB.CboCollections.SingleAsync();
        Assert.Null(again.ForwardClaimedUntil);
        Assert.True(await ForwardingClaim.TryClaimAsync(workerB, again, _time.Now, Lease, default));
    }

    [Fact]
    public void TheDefaultLease_OutlastsTheFoodspaceTimeout()
    {
        var defaults = new FoodspaceOptions();

        Assert.True(defaults.ClaimLeaseSeconds >= defaults.TimeoutSeconds * 2,
            "A lease shorter than the Foodspace call lets a second worker send the record while the first is still waiting.");
    }

    // ── collections ────────────────────────────────────────────────────────────────

    [Fact]
    public async Task TwoForwardersRunningAtOnce_SendEveryCollectionExactlyOnce()
    {
        await SeedAsync(Enumerable.Range(1, 12).Select(i => (ForwardedEntity)Collection($"c{i:D2}")).ToArray());
        _client.Delay = TimeSpan.FromMilliseconds(15);
        await using var dbA = NewDb();
        await using var dbB = NewDb();

        var attempted = await Task.WhenAll(Collections(dbA).ForwardDueAsync(), Collections(dbB).ForwardDueAsync());

        Assert.Equal(12, _client.Sent);
        Assert.Equal(12, attempted.Sum()); // what each reports is what it really sent, so the two add up to the total
        await using var check = NewDb();
        var all = await check.CboCollections.ToListAsync();
        Assert.All(all, c =>
        {
            Assert.Equal(ForwardingStatus.Forwarded, c.ForwardingStatus);
            Assert.Equal(1, c.SyncAttempts);
            Assert.Null(c.ForwardClaimedUntil);
        });
    }

    [Fact]
    public async Task ACollectionAnotherWorkerIsSendingRightNow_IsLeftAlone()
    {
        await SeedAsync(Collection("c1", claimedUntil: _time.Now.AddSeconds(60)));
        await using var db = NewDb();
        var forwarder = Collections(db);

        Assert.Equal(0, await forwarder.ForwardDueAsync());
        Assert.Equal(ForwardingStatus.Pending, await forwarder.ForwardAsync("c1")); // "now" does not push past someone else's send
        Assert.Equal(ForwardingStatus.Pending, await forwarder.RetryAsync("c1"));    // nor does an Admin's retry

        Assert.Equal(0, _client.Sent);
    }

    [Fact]
    public async Task AnAdminRetryThatLosesTheClaim_DoesNotResetTheAttemptCount()
    {
        await SeedAsync(new CboCollection
        {
            Id = "c1", CboId = "cbo-1", ArrivalTime = "10:00", DonorName = "D", DeliveryNote = "DN", CollectNotes = "",
            ForwardingStatus = ForwardingStatus.SyncedLocalPendingFoodspace, SyncAttempts = 5,
            ForwardClaimId = Guid.NewGuid(), ForwardClaimedUntil = _time.Now.AddSeconds(60),
        });
        await using var db = NewDb();

        await Collections(db).RetryAsync("c1");

        await using var check = NewDb();
        Assert.Equal(5, (await check.CboCollections.SingleAsync()).SyncAttempts);
    }

    [Fact]
    public async Task ACollectionWhoseWorkerDied_IsTakenOverOnceTheLeaseHasRunOut()
    {
        await SeedAsync(Collection("c1", claimedUntil: _time.Now.AddSeconds(-1)));
        await using var db = NewDb();

        Assert.Equal(1, await Collections(db).ForwardDueAsync());

        Assert.Equal(1, _client.Sent);
        await using var check = NewDb();
        var saved = await check.CboCollections.SingleAsync();
        Assert.Equal(ForwardingStatus.Forwarded, saved.ForwardingStatus);
        Assert.Null(saved.ForwardClaimedUntil);
    }

    [Fact]
    public async Task AFailedSend_AlsoEndsTheClaim_SoTheRetryIsNotHeldUp()
    {
        await SeedAsync(Collection("c1"));
        _client.Answer = new FoodspaceResult(FoodspaceOutcome.Transient, "Foodspace could not be reached");
        await using var db = NewDb();

        await Collections(db).ForwardDueAsync();

        await using var check = NewDb();
        var saved = await check.CboCollections.SingleAsync();
        Assert.Equal(ForwardingStatus.SyncedLocalPendingFoodspace, saved.ForwardingStatus);
        Assert.NotNull(saved.NextForwardAttemptAt);
        Assert.Null(saved.ForwardClaimedUntil);
    }

    [Fact]
    public async Task IfTheClaimIsTakenOverMidSend_TheLateOutcomeIsDropped_NotWrittenOverTheNewOwnersWork()
    {
        await SeedAsync(Collection("c1"));
        var takeover = Guid.NewGuid();
        _client.DuringSend = async () =>
        {
            // Foodspace is slow: the lease runs out and another worker takes the record over while this one waits.
            await using var other = NewDb();
            var row = await other.CboCollections.SingleAsync();
            row.ForwardClaimId = takeover;
            row.ForwardClaimedUntil = _time.Now.AddSeconds(120);
            await other.SaveChangesAsync();
        };
        await using var db = NewDb();

        var attempted = await Collections(db).ForwardDueAsync();

        Assert.Equal(1, attempted);
        await using var check = NewDb();
        var saved = await check.CboCollections.SingleAsync();
        Assert.Equal(takeover, saved.ForwardClaimId);              // still the new owner's
        Assert.Equal(ForwardingStatus.Pending, saved.ForwardingStatus); // the late "Forwarded" was not written
    }

    // ── vetting decisions ──────────────────────────────────────────────────────────

    [Fact]
    public async Task TwoForwardersRunningAtOnce_SendEveryDecisionExactlyOnce()
    {
        await SeedAsync(Enumerable.Range(1, 8).Select(i => (ForwardedEntity)Decision($"d{i}", record: $"rec-{i}", at: i)).ToArray());
        _client.Delay = TimeSpan.FromMilliseconds(15);
        await using var dbA = NewDb();
        await using var dbB = NewDb();

        var attempted = await Task.WhenAll(Decisions(dbA).ForwardDueAsync(), Decisions(dbB).ForwardDueAsync());

        Assert.Equal(8, _client.Sent);
        Assert.Equal(8, attempted.Sum());
        await using var check = NewDb();
        Assert.All(await check.VettingDecisions.ToListAsync(), d =>
        {
            Assert.Equal(ForwardingStatus.Forwarded, d.ForwardingStatus);
            Assert.Equal(1, d.SyncAttempts);
            Assert.Null(d.ForwardClaimedUntil);
        });
    }

    [Fact]
    public async Task ADecisionAnotherWorkerIsSendingRightNow_IsLeftAlone()
    {
        await SeedAsync(Decision("d1", claimedUntil: _time.Now.AddSeconds(60)));
        await using var db = NewDb();
        var forwarder = Decisions(db);

        Assert.Equal(0, await forwarder.ForwardDueAsync());
        Assert.Equal(ForwardingStatus.Pending, await forwarder.ForwardAsync("d1"));

        Assert.Equal(0, _client.Sent);
    }

    [Fact]
    public async Task ASupersededDecision_EndsItsClaim_WithoutBeingSent()
    {
        await SeedAsync(Decision("old", at: 1), Decision("new", at: 2, claimedUntil: _time.Now.AddSeconds(60))); // "new" is busy elsewhere
        await using var db = NewDb();

        await Decisions(db).ForwardDueAsync();

        Assert.Equal(0, _client.Sent);
        await using var check = NewDb();
        var old = await check.VettingDecisions.SingleAsync(d => d.Id == "old");
        Assert.Equal(ForwardingStatus.Superseded, old.ForwardingStatus);
        Assert.Null(old.ForwardClaimedUntil);
    }

    // ── an Admin acting while a worker holds the record ────────────────────────────

    [Fact]
    public async Task AnAdminDismissingARecordAWorkerJustClaimed_GetsAConflict_NotAServerError()
    {
        await SeedAsync(new CboCollection
        {
            Id = "c1", CboId = "cbo-1", ArrivalTime = "10:00", DonorName = "D", DeliveryNote = "DN", CollectNotes = "",
            ForwardingStatus = ForwardingStatus.SyncedLocalPendingFoodspace, SyncAttempts = 8, NextForwardAttemptAt = null, // needs an Admin
        });

        // The worker claims the record after the Admin's request has read it and before it writes.
        var workerClaims = new BeforeFirstSave(async () =>
        {
            await using var worker = NewDb();
            var row = await worker.CboCollections.SingleAsync();
            Assert.True(await ForwardingClaim.TryClaimAsync(worker, row, _time.Now, Lease, default));
        });
        await using var adminDb = NewDb(workerClaims);
        var service = new AdminSyncResolutionService(adminDb, Collections(adminDb), Decisions(adminDb), _time, NullLogger<AdminSyncResolutionService>.Instance);

        var result = await service.DismissAsync("c1", SyncForm.CboCollection, "handled outside the app", "admin_test_user");

        Assert.Equal(ResolutionStatus.NotAllowed, result.Status); // the controller turns this into 409 Conflict
        Assert.Contains("right now", result.Message);
        await using var check = NewDb();
        Assert.NotEqual(ForwardingStatus.Dismissed, (await check.CboCollections.SingleAsync()).ForwardingStatus);
    }
}
