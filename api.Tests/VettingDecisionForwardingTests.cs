using System.Net;
using System.Text.Json;
using api.Data;
using api.Models;
using api.Services.Foodspace;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace api.Tests;

/// <summary>Forwarding vetting decisions to Foodspace: the payload, outages and retries, and "only the latest decision is sent".</summary>
public class VettingDecisionForwardingTests
{
    // ── helpers ────────────────────────────────────────────────────────────────────

    private sealed class ManualTime : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = new(2026, 10, 1, 8, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private sealed class StubHandler(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
    {
        public List<HttpRequestMessage> Requests { get; } = new();
        public List<string> Bodies { get; } = new();

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Requests.Add(request);
            Bodies.Add(request.Content is null ? "" : await request.Content.ReadAsStringAsync(ct));
            return respond(request);
        }
    }

    /// <summary>Records every decision it is asked to send, and answers as configured.</summary>
    private sealed class FakeClient(Func<FoodspaceResult> result) : IFoodspaceApiClient
    {
        public List<string> Sent { get; } = new();

        public Task<FoodspaceResult> SubmitVettingDecisionAsync(VettingDecision decision, CancellationToken ct = default)
        {
            Sent.Add(decision.Id);
            return Task.FromResult(result());
        }

        public Task<FoodspaceResult> SubmitCboCollectionAsync(CboCollection collection, CancellationToken ct = default) => throw new NotSupportedException();
        public Task<FoodspaceBeneficiariesResult> GetBeneficiariesAsync(CancellationToken ct = default) => throw new NotSupportedException();
    }

    private static FoodspaceOptions Options(Action<FoodspaceOptions>? tweak = null)
    {
        var o = new FoodspaceOptions { BaseUrl = "http://foodspace.test", MaxAttempts = 3, BaseDelaySeconds = 30, MaxDelaySeconds = 3600 };
        tweak?.Invoke(o);
        return o;
    }

    private static VettingDecision Decision(
        string id, string record = "fs-1001", DecisionOutcome outcome = DecisionOutcome.Approve, long at = 1_000, long created = 0, string? notes = "ok") => new()
    {
        Id = id,
        FoodspaceRecordId = record,
        Outcome = outcome,
        Notes = notes,
        OfficerId = "vetting_test_user",
        DecisionTimestamp = at,
        CreatedAt = created == 0 ? at : created,
        UpdatedAt = at,
        SyncStatus = SyncStatus.Synced,
    };

    private static AppDbContext NewDb() =>
        new(new DbContextOptionsBuilder<AppDbContext>().UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);

    private static (VettingDecisionForwarder Forwarder, AppDbContext Db, ManualTime Time) NewForwarder(IFoodspaceApiClient client, FoodspaceOptions? options = null)
    {
        var db = NewDb();
        var time = new ManualTime();
        var forwarder = new VettingDecisionForwarder(
            db, client, Microsoft.Extensions.Options.Options.Create(options ?? Options()), time, NullLogger<VettingDecisionForwarder>.Instance);
        return (forwarder, db, time);
    }

    private static FoodspaceApiClient NewHttpClient(StubHandler handler)
    {
        var options = Options();
        var http = new HttpClient(handler) { BaseAddress = new Uri(options.BaseUrl!) };
        return new FoodspaceApiClient(http, Microsoft.Extensions.Options.Options.Create(options), NullLogger<FoodspaceApiClient>.Instance);
    }

    private static HttpResponseMessage Status(HttpStatusCode code) => new(code) { Content = new StringContent("{}") };

    // ── the payload ────────────────────────────────────────────────────────────────

    [Fact]
    public void Mapper_CarriesEveryFieldAcross_AndNothingServerOnly()
    {
        var d = Decision("d1", "fs-77", DecisionOutcome.Flag, at: 123, created: 100, notes: "needs a second look");
        d.UpdatedAt = 200;
        d.SyncAttempts = 4; d.SyncError = "secret detail"; d.ForwardingStatus = ForwardingStatus.Forwarded;

        var json = JsonSerializer.Serialize(FoodspaceVettingDecisionMapper.ToFoodspace(d), new JsonSerializerOptions(JsonSerializerDefaults.Web));
        using var doc = JsonDocument.Parse(json);
        var p = doc.RootElement;

        Assert.Equal("d1", p.GetProperty("id").GetString());
        Assert.Equal("fs-77", p.GetProperty("foodspaceRecordId").GetString());
        Assert.Equal("FLAG", p.GetProperty("outcome").GetString());
        Assert.Equal("needs a second look", p.GetProperty("notes").GetString());
        Assert.Equal("vetting_test_user", p.GetProperty("officerId").GetString());
        Assert.Equal(123, p.GetProperty("decisionTimestamp").GetInt64());
        Assert.Equal(100, p.GetProperty("createdAt").GetInt64());
        Assert.Equal(200, p.GetProperty("updatedAt").GetInt64());
        Assert.Equal(8, p.EnumerateObject().Count()); // and nothing else: no sync status, attempts or errors
        Assert.DoesNotContain("secret detail", json);
    }

    [Theory]
    [InlineData(DecisionOutcome.Approve, "APPROVE")]
    [InlineData(DecisionOutcome.Reject, "REJECT")]
    [InlineData(DecisionOutcome.Flag, "FLAG")]
    public void TheOutcome_IsSentAsFoodspaceExpectsIt(DecisionOutcome outcome, string expected) =>
        Assert.Equal(expected, FoodspaceVettingDecisionMapper.ToFoodspace(Decision("d", outcome: outcome)).Outcome);

    [Fact]
    public async Task Client_PostsCamelCaseJson_ToTheVettingDecisionsEndpoint()
    {
        var handler = new StubHandler(_ => Status(HttpStatusCode.OK));

        var result = await NewHttpClient(handler).SubmitVettingDecisionAsync(Decision("d1"));

        Assert.Equal(FoodspaceOutcome.Success, result.Outcome);
        var request = handler.Requests.Single();
        Assert.Equal(HttpMethod.Post, request.Method);
        Assert.EndsWith("api/external/vetting-decisions", request.RequestUri!.AbsolutePath.TrimStart('/'));
        using var doc = JsonDocument.Parse(handler.Bodies.Single());
        Assert.Equal("fs-1001", doc.RootElement.GetProperty("foodspaceRecordId").GetString());
    }

    [Theory]
    [InlineData(500)]
    [InlineData(503)]
    [InlineData(429)]
    [InlineData(408)]
    [InlineData(401)]
    [InlineData(403)]
    public async Task Client_OutagesThrottlingAndAuthProblems_AreTransient(int status)
    {
        var result = await NewHttpClient(new StubHandler(_ => Status((HttpStatusCode)status))).SubmitVettingDecisionAsync(Decision("d1"));

        Assert.Equal(FoodspaceOutcome.Transient, result.Outcome);
    }

    [Theory]
    [InlineData(400)]
    [InlineData(404)]
    [InlineData(422)]
    public async Task Client_ARejectedDecision_IsPermanent_AndNeverStoresTheResponseBody(int status)
    {
        var handler = new StubHandler(_ => new HttpResponseMessage((HttpStatusCode)status) { Content = new StringContent("officer notes and applicant data") });

        var result = await NewHttpClient(handler).SubmitVettingDecisionAsync(Decision("d1"));

        Assert.Equal(FoodspaceOutcome.Permanent, result.Outcome);
        Assert.DoesNotContain("applicant data", result.Error);
    }

    [Fact]
    public async Task Client_WhenFoodspaceIsUnreachable_ReturnsTransient_InsteadOfThrowing()
    {
        var handler = new StubHandler(_ => throw new HttpRequestException("connection refused: secret-host.internal"));

        var result = await NewHttpClient(handler).SubmitVettingDecisionAsync(Decision("d1"));

        Assert.Equal(FoodspaceOutcome.Transient, result.Outcome);
        Assert.DoesNotContain("secret-host", result.Error);
    }

    // ── forwarding ─────────────────────────────────────────────────────────────────

    [Fact]
    public async Task Success_MarksTheDecisionForwarded()
    {
        var client = new FakeClient(() => FoodspaceResult.Ok);
        var (forwarder, db, _) = NewForwarder(client);
        db.VettingDecisions.Add(Decision("d1"));
        await db.SaveChangesAsync();

        Assert.Equal(1, await forwarder.ForwardDueAsync());

        var saved = await db.VettingDecisions.SingleAsync();
        Assert.Equal(ForwardingStatus.Forwarded, saved.ForwardingStatus);
        Assert.Equal(1, saved.SyncAttempts);
        Assert.Null(saved.NextForwardAttemptAt);
        Assert.Null(saved.SyncError);
        Assert.Equal(SyncStatus.Synced, saved.SyncStatus);
    }

    [Fact]
    public async Task FoodspaceOutage_KeepsTheDecision_AsSyncedLocalPendingFoodspace_NotFailed()
    {
        var client = new FakeClient(() => new FoodspaceResult(FoodspaceOutcome.Transient, "Foodspace answered 503 (Service Unavailable)"));
        var (forwarder, db, time) = NewForwarder(client);
        db.VettingDecisions.Add(Decision("d1"));
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();

        var saved = await db.VettingDecisions.SingleAsync();
        Assert.Equal(ForwardingStatus.SyncedLocalPendingFoodspace, saved.ForwardingStatus);
        Assert.Equal(SyncStatus.Synced, saved.SyncStatus);          // the officer's decision is safe: never "failed"
        Assert.Equal("Foodspace answered 503 (Service Unavailable)", saved.SyncError);
        Assert.Equal(time.Now + TimeSpan.FromSeconds(30), saved.NextForwardAttemptAt);
        Assert.Equal("ok", saved.Notes);                              // nothing was lost
    }

    [Fact]
    public async Task ItRetriesOnlyOnceTheBackoffHasElapsed_AndThenRecovers()
    {
        var healthy = false;
        var client = new FakeClient(() => healthy ? FoodspaceResult.Ok : new FoodspaceResult(FoodspaceOutcome.Transient, "down"));
        var (forwarder, db, time) = NewForwarder(client);
        db.VettingDecisions.Add(Decision("d1"));
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();                   // attempt 1: fails
        time.Now += TimeSpan.FromSeconds(10);
        Assert.Equal(0, await forwarder.ForwardDueAsync());  // too early
        healthy = true;
        time.Now += TimeSpan.FromSeconds(30);
        Assert.Equal(1, await forwarder.ForwardDueAsync());  // due, and Foodspace is back

        var saved = await db.VettingDecisions.SingleAsync();
        Assert.Equal(ForwardingStatus.Forwarded, saved.ForwardingStatus);
        Assert.Equal(2, saved.SyncAttempts);
        Assert.Equal(2, client.Sent.Count);
    }

    [Fact]
    public async Task TheBackoff_IsTheSameOneCollectionsUse()
    {
        var options = Options();
        var (forwarder, db, time) = NewForwarder(new FakeClient(() => new FoodspaceResult(FoodspaceOutcome.Transient, "down")), options);
        db.VettingDecisions.Add(Decision("d1"));
        await db.SaveChangesAsync();

        var expected = new List<TimeSpan>();
        for (var attempt = 1; attempt <= 2; attempt++)
        {
            await forwarder.ForwardDueAsync();
            expected.Add(ForwardingBackoff.For(options, attempt));
            var saved = await db.VettingDecisions.SingleAsync();
            Assert.Equal(time.Now + expected[^1], saved.NextForwardAttemptAt);
            time.Now = saved.NextForwardAttemptAt!.Value;
        }
        Assert.Equal(new[] { TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(60) }, expected);
    }

    [Fact]
    public async Task AfterTheLastAttempt_ItStopsRetrying_ButTheDecisionStaysVisibleAsPendingFoodspace()
    {
        var client = new FakeClient(() => new FoodspaceResult(FoodspaceOutcome.Transient, "down"));
        var (forwarder, db, time) = NewForwarder(client, Options(o => o.MaxAttempts = 2));
        db.VettingDecisions.Add(Decision("d1"));
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();
        time.Now += TimeSpan.FromHours(1);
        await forwarder.ForwardDueAsync();
        time.Now += TimeSpan.FromDays(1);
        Assert.Equal(0, await forwarder.ForwardDueAsync());

        var saved = await db.VettingDecisions.SingleAsync();
        Assert.Equal(ForwardingStatus.SyncedLocalPendingFoodspace, saved.ForwardingStatus);
        Assert.Null(saved.NextForwardAttemptAt);
        Assert.Equal(2, client.Sent.Count);
    }

    [Fact]
    public async Task ADecisionFoodspaceRejects_IsNotRetriedAutomatically_ButStaysVisible()
    {
        var client = new FakeClient(() => new FoodspaceResult(FoodspaceOutcome.Permanent, "Foodspace answered 400 (Bad Request)"));
        var (forwarder, db, time) = NewForwarder(client);
        db.VettingDecisions.Add(Decision("d1", record: "fs-unknown-to-foodspace"));
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();
        time.Now += TimeSpan.FromDays(1);
        Assert.Equal(0, await forwarder.ForwardDueAsync());

        var saved = await db.VettingDecisions.SingleAsync();
        Assert.Equal(ForwardingStatus.SyncedLocalPendingFoodspace, saved.ForwardingStatus);
        Assert.Equal("Foodspace answered 400 (Bad Request)", saved.SyncError);
        Assert.Null(saved.NextForwardAttemptAt);
        Assert.Single(client.Sent);
    }

    [Fact]
    public async Task AForwardedDecision_IsNeverSentAgain()
    {
        var client = new FakeClient(() => FoodspaceResult.Ok);
        var (forwarder, db, time) = NewForwarder(client);
        db.VettingDecisions.Add(Decision("d1"));
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();
        time.Now += TimeSpan.FromDays(1);
        Assert.Equal(0, await forwarder.ForwardDueAsync());

        Assert.Single(client.Sent);
    }

    [Fact]
    public async Task ManualForward_AttemptsEvenWhenRetriesAreExhausted_AndCanRecover()
    {
        var healthy = false;
        var client = new FakeClient(() => healthy ? FoodspaceResult.Ok : new FoodspaceResult(FoodspaceOutcome.Transient, "down"));
        var (forwarder, db, time) = NewForwarder(client, Options(o => o.MaxAttempts = 1));
        db.VettingDecisions.Add(Decision("d1"));
        await db.SaveChangesAsync();
        await forwarder.ForwardDueAsync();           // the only automatic attempt: exhausted
        time.Now += TimeSpan.FromDays(1);
        healthy = true;

        var status = await forwarder.ForwardAsync("d1");

        Assert.Equal(ForwardingStatus.Forwarded, status);
        Assert.Null(await forwarder.ForwardAsync("no-such-id"));
    }

    // ── only the latest decision on a beneficiary is sent ──────────────────────────

    [Fact]
    public async Task WhenTheOfficerChangedTheirMind_OnlyTheNewestDecisionIsSent_TheOlderIsKeptAsHistory()
    {
        var client = new FakeClient(() => FoodspaceResult.Ok);
        var (forwarder, db, _) = NewForwarder(client);
        db.VettingDecisions.AddRange(
            Decision("flagged", at: 1_000, outcome: DecisionOutcome.Flag),
            Decision("approved", at: 2_000, outcome: DecisionOutcome.Approve));
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();

        Assert.Equal(new[] { "approved" }, client.Sent);
        var all = await db.VettingDecisions.ToDictionaryAsync(d => d.Id);
        Assert.Equal(ForwardingStatus.Superseded, all["flagged"].ForwardingStatus);
        Assert.Equal(ForwardingStatus.Forwarded, all["approved"].ForwardingStatus);
        Assert.Equal(DecisionOutcome.Flag, all["flagged"].Outcome); // still stored, untouched
        Assert.Null(all["flagged"].NextForwardAttemptAt);
        Assert.Equal(0, all["flagged"].SyncAttempts);               // Foodspace was never asked
    }

    [Fact]
    public async Task ADecisionThatArrivesLate_ButWasMadeEarlier_IsNotSent()
    {
        var client = new FakeClient(() => FoodspaceResult.Ok);
        var (forwarder, db, _) = NewForwarder(client);
        db.VettingDecisions.Add(Decision("new", at: 5_000, outcome: DecisionOutcome.Approve));
        await db.SaveChangesAsync();
        await forwarder.ForwardDueAsync(); // the newer one went first

        db.VettingDecisions.Add(Decision("old-arrives-late", at: 1_000, outcome: DecisionOutcome.Reject)); // an offline device syncing late
        await db.SaveChangesAsync();
        await forwarder.ForwardDueAsync();

        Assert.Equal(new[] { "new" }, client.Sent);
        Assert.Equal(ForwardingStatus.Superseded, (await db.VettingDecisions.SingleAsync(d => d.Id == "old-arrives-late")).ForwardingStatus);
    }

    [Fact]
    public async Task ADecisionAlreadyForwarded_IsLeftAlone_WhenANewerOneLaterGoesOut()
    {
        var client = new FakeClient(() => FoodspaceResult.Ok);
        var (forwarder, db, _) = NewForwarder(client);
        db.VettingDecisions.Add(Decision("first", at: 1_000, outcome: DecisionOutcome.Flag));
        await db.SaveChangesAsync();
        await forwarder.ForwardDueAsync();

        db.VettingDecisions.Add(Decision("second", at: 2_000, outcome: DecisionOutcome.Approve));
        await db.SaveChangesAsync();
        await forwarder.ForwardDueAsync();

        Assert.Equal(new[] { "first", "second" }, client.Sent);   // Foodspace saw them in order: its last word is "approved"
        Assert.All(await db.VettingDecisions.ToListAsync(), d => Assert.Equal(ForwardingStatus.Forwarded, d.ForwardingStatus));
    }

    [Fact]
    public async Task ADecisionWaitingForARetry_BecomesSuperseded_WhenANewerOneArrives()
    {
        var healthy = false;
        var client = new FakeClient(() => healthy ? FoodspaceResult.Ok : new FoodspaceResult(FoodspaceOutcome.Transient, "down"));
        var (forwarder, db, time) = NewForwarder(client);
        db.VettingDecisions.Add(Decision("old", at: 1_000, outcome: DecisionOutcome.Flag));
        await db.SaveChangesAsync();
        await forwarder.ForwardDueAsync(); // fails; a retry is scheduled

        db.VettingDecisions.Add(Decision("new", at: 2_000, outcome: DecisionOutcome.Approve));
        await db.SaveChangesAsync();
        healthy = true;
        time.Now += TimeSpan.FromMinutes(5);
        await forwarder.ForwardDueAsync();

        Assert.Equal(new[] { "old", "new" }, client.Sent); // "old" was tried once, before "new" existed; never again
        var all = await db.VettingDecisions.ToDictionaryAsync(d => d.Id);
        Assert.Equal(ForwardingStatus.Superseded, all["old"].ForwardingStatus);
        Assert.Null(all["old"].NextForwardAttemptAt);
        Assert.Null(all["old"].SyncError);
        Assert.Equal(ForwardingStatus.Forwarded, all["new"].ForwardingStatus);
    }

    [Fact]
    public async Task DecisionsOnDifferentBeneficiaries_AreAllSent()
    {
        var client = new FakeClient(() => FoodspaceResult.Ok);
        var (forwarder, db, _) = NewForwarder(client);
        db.VettingDecisions.AddRange(Decision("a", record: "fs-1", at: 1_000), Decision("b", record: "fs-2", at: 2_000), Decision("c", record: "fs-3", at: 3_000));
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();

        Assert.Equal(new[] { "a", "b", "c" }, client.Sent);
        Assert.All(await db.VettingDecisions.ToListAsync(), d => Assert.Equal(ForwardingStatus.Forwarded, d.ForwardingStatus));
    }

    [Fact]
    public async Task WhenTwoDecisionsShareATimestamp_TheOneCreatedLaterWins()
    {
        var client = new FakeClient(() => FoodspaceResult.Ok);
        var (forwarder, db, _) = NewForwarder(client);
        db.VettingDecisions.AddRange(
            Decision("earlier-created", at: 1_000, created: 1_000, outcome: DecisionOutcome.Flag),
            Decision("later-created", at: 1_000, created: 1_005, outcome: DecisionOutcome.Approve));
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();

        Assert.Equal(new[] { "later-created" }, client.Sent);
    }

    [Fact]
    public async Task ASupersededDecision_IsNotSentEvenByAManualForward()
    {
        var client = new FakeClient(() => FoodspaceResult.Ok);
        var (forwarder, db, _) = NewForwarder(client);
        db.VettingDecisions.AddRange(Decision("old", at: 1_000), Decision("new", at: 2_000));
        await db.SaveChangesAsync();

        var status = await forwarder.ForwardAsync("old");

        Assert.Equal(ForwardingStatus.Superseded, status);
        Assert.Empty(client.Sent);
    }

    [Fact]
    public async Task OlderDecisionsAreHandledFirst_SoFoodspaceEndsOnTheNewest()
    {
        var client = new FakeClient(() => FoodspaceResult.Ok);
        var (forwarder, db, _) = NewForwarder(client);
        db.VettingDecisions.AddRange(
            Decision("c", record: "fs-3", at: 3_000), Decision("a", record: "fs-1", at: 1_000), Decision("b", record: "fs-2", at: 2_000));
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();

        Assert.Equal(new[] { "a", "b", "c" }, client.Sent);
    }

    [Fact]
    public void Superseded_IsStoredAsUpperSnakeCase()
    {
        var converter = new UpperSnakeEnumConverter<ForwardingStatus>();

        Assert.Equal("SUPERSEDED", converter.ConvertToProvider(ForwardingStatus.Superseded));
        Assert.Equal(ForwardingStatus.Superseded, converter.ConvertFromProvider("SUPERSEDED"));
    }
}
