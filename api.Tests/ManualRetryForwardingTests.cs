using api.Data;
using api.Models;
using api.Services.Foodspace;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace api.Tests;

/// <summary>
/// What an Admin's manual retry (#50) changes in the forwarders themselves: it starts the attempt count again, and a
/// released suspected duplicate is retried like any other record while a held one is never sent on its own.
/// </summary>
public class ManualRetryForwardingTests
{
    private sealed class ManualTime : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = new(2026, 10, 1, 8, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private sealed class FakeClient : IFoodspaceApiClient
    {
        public FoodspaceResult Answer { get; set; } = FoodspaceResult.Ok;
        public List<string> Sent { get; } = new();

        public Task<FoodspaceResult> SubmitCboCollectionAsync(CboCollection collection, CancellationToken ct = default)
        {
            Sent.Add(collection.Id);
            return Task.FromResult(Answer);
        }

        public Task<FoodspaceResult> SubmitVettingDecisionAsync(VettingDecision decision, CancellationToken ct = default)
        {
            Sent.Add(decision.Id);
            return Task.FromResult(Answer);
        }

        public Task<FoodspaceBeneficiariesResult> GetBeneficiariesAsync(CancellationToken ct = default) =>
            throw new NotSupportedException();
    }

    private static readonly FoodspaceResult Down = new(FoodspaceOutcome.Transient, "Foodspace could not be reached");

    private readonly AppDbContext _db = new(new DbContextOptionsBuilder<AppDbContext>().UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
    private readonly ManualTime _time = new();
    private readonly FakeClient _client = new();
    private readonly IOptions<FoodspaceOptions> _options =
        Microsoft.Extensions.Options.Options.Create(new FoodspaceOptions { BaseUrl = "http://foodspace.test", MaxAttempts = 3, BaseDelaySeconds = 30, MaxDelaySeconds = 3600 });

    private CboCollectionForwarder Collections() => new(_db, _client, _options, _time, NullLogger<CboCollectionForwarder>.Instance);
    private VettingDecisionForwarder Decisions() => new(_db, _client, _options, _time, NullLogger<VettingDecisionForwarder>.Instance);

    private static CboCollection Collection(string id, ForwardingStatus status, int attempts, DateTimeOffset? next = null, string? duplicateOf = null) => new()
    {
        Id = id, CboId = "cbo-1", ArrivalTime = "10:00", DonorName = "Donor", DeliveryNote = "DN", CollectNotes = "",
        ForwardingStatus = status, SyncAttempts = attempts, NextForwardAttemptAt = next, DuplicateOfId = duplicateOf,
    };

    private static VettingDecision Decision(string id, ForwardingStatus status, int attempts) => new()
    {
        Id = id, FoodspaceRecordId = "fs-1", OfficerId = "officer", Outcome = DecisionOutcome.Approve,
        DecisionTimestamp = 1, CreatedAt = 1, ForwardingStatus = status, SyncAttempts = attempts,
    };

    // ── the attempt count ──────────────────────────────────────────────────────────

    [Fact]
    public async Task ACollectionRetry_StartsTheAttemptCountAgain_SoItGetsAFreshAutomaticRetryBudget()
    {
        _db.CboCollections.Add(Collection("c1", ForwardingStatus.SyncedLocalPendingFoodspace, attempts: 3)); // MaxAttempts used up, no retry scheduled
        await _db.SaveChangesAsync();
        _client.Answer = Down;

        var status = await Collections().RetryAsync("c1");

        var stored = await _db.CboCollections.SingleAsync();
        Assert.Equal(ForwardingStatus.SyncedLocalPendingFoodspace, status);
        Assert.Equal(1, stored.SyncAttempts);
        Assert.Equal(_time.Now.AddSeconds(30), stored.NextForwardAttemptAt); // back on the normal backoff schedule
    }

    [Fact]
    public async Task ADecisionRetry_StartsTheAttemptCountAgain_SoItGetsAFreshAutomaticRetryBudget()
    {
        _db.VettingDecisions.Add(Decision("d1", ForwardingStatus.SyncedLocalPendingFoodspace, attempts: 3));
        await _db.SaveChangesAsync();
        _client.Answer = Down;

        await Decisions().RetryAsync("d1");

        var stored = await _db.VettingDecisions.SingleAsync();
        Assert.Equal(1, stored.SyncAttempts);
        Assert.Equal(_time.Now.AddSeconds(30), stored.NextForwardAttemptAt);
    }

    [Fact]
    public async Task ARetry_ThatIsRejectedAgain_LeavesNoRetryScheduled()
    {
        _db.CboCollections.Add(Collection("c1", ForwardingStatus.SyncedLocalPendingFoodspace, attempts: 3));
        await _db.SaveChangesAsync();
        _client.Answer = new FoodspaceResult(FoodspaceOutcome.Permanent, "Foodspace answered 422 (Unprocessable Entity)");

        await Collections().RetryAsync("c1");

        var stored = await _db.CboCollections.SingleAsync();
        Assert.Null(stored.NextForwardAttemptAt);
        Assert.Equal("Foodspace answered 422 (Unprocessable Entity)", stored.SyncError);
    }

    [Fact]
    public async Task ARetry_OfAnUnknownRecord_ReturnsNull()
    {
        Assert.Null(await Collections().RetryAsync("nope"));
        Assert.Null(await Decisions().RetryAsync("nope"));
    }

    // ── suspected duplicates ───────────────────────────────────────────────────────

    [Fact]
    public async Task AHeldDuplicate_IsNeverSentByTheBackgroundLoop()
    {
        _db.CboCollections.Add(Collection("dup", ForwardingStatus.Pending, attempts: 0, duplicateOf: "orig"));
        await _db.SaveChangesAsync();

        Assert.Equal(0, await Collections().ForwardDueAsync());

        Assert.Empty(_client.Sent);
    }

    [Fact]
    public async Task AReleasedDuplicate_ThatFailedTransiently_IsRetriedByTheBackgroundLoopLikeAnyOtherRecord()
    {
        _db.CboCollections.Add(Collection("dup", ForwardingStatus.Pending, attempts: 0, duplicateOf: "orig"));
        await _db.SaveChangesAsync();
        _client.Answer = Down;
        await Collections().RetryAsync("dup"); // the Admin releases it; Foodspace is down, so a retry is scheduled

        _time.Now += TimeSpan.FromMinutes(5);
        _client.Answer = FoodspaceResult.Ok;
        var attempted = await Collections().ForwardDueAsync();

        Assert.Equal(1, attempted);
        Assert.Equal(ForwardingStatus.Forwarded, (await _db.CboCollections.SingleAsync()).ForwardingStatus);
    }

    [Fact]
    public async Task ADismissedRecord_IsNeverSentByTheBackgroundLoop()
    {
        _db.CboCollections.Add(Collection("c1", ForwardingStatus.Dismissed, attempts: 3));
        _db.VettingDecisions.Add(Decision("d1", ForwardingStatus.Dismissed, attempts: 2));
        await _db.SaveChangesAsync();

        Assert.Equal(0, await Collections().ForwardDueAsync());
        Assert.Equal(0, await Decisions().ForwardDueAsync());
        Assert.Empty(_client.Sent);
    }
}
