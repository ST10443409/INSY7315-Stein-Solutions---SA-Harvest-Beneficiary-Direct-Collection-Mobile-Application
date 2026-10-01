using System.Net;
using System.Text.Json;
using api.Data;
using api.Models;
using api.Services.Foodspace;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace api.Tests;

public class FoodspaceForwardingTests
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

    private sealed class FakeClient(Func<FoodspaceResult> result) : IFoodspaceApiClient
    {
        public int Calls { get; private set; }

        public Task<FoodspaceResult> SubmitVettingDecisionAsync(VettingDecision decision, CancellationToken ct = default) =>
            throw new NotSupportedException("not used by the collection forwarding tests");

        public Task<FoodspaceBeneficiariesResult> GetBeneficiariesAsync(CancellationToken ct = default) =>
            throw new NotSupportedException("not used by the forwarding tests");

        public Task<FoodspaceResult> SubmitCboCollectionAsync(CboCollection collection, CancellationToken ct = default)
        {
            Calls++;
            return Task.FromResult(result());
        }
    }

    private static FoodspaceOptions Options(Action<FoodspaceOptions>? tweak = null)
    {
        var o = new FoodspaceOptions { BaseUrl = "http://foodspace.test", MaxAttempts = 3, BaseDelaySeconds = 30, MaxDelaySeconds = 3600 };
        tweak?.Invoke(o);
        return o;
    }

    private static CboCollection Collection(string id = "c1") => new()
    {
        Id = id,
        CboId = "cbo-001",
        ArrivalTime = "09:00",
        DepartureTime = "10:00",
        DonorName = "Jane Donor",
        DonorSigned = true,
        CboSigned = true,
        DeliveryNote = "DN-1",
        NoteAttached = false,
        CollectNotes = "n",
        Shots = new List<bool> { true, false },
        Latitude = -33.9,
        Longitude = 18.4,
        CreatedAt = 111,
        UpdatedAt = 222,
        SyncStatus = SyncStatus.Synced,
        ProductLines = new List<ProductLine>
        {
            new() { Id = "p1", CollectionId = id, Category = "Fruit", Kg = "42.5", Notes = null, CreatedAt = 1, UpdatedAt = 2 },
        },
    };

    private static AppDbContext NewDb() =>
        new(new DbContextOptionsBuilder<AppDbContext>().UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);

    private static FoodspaceApiClient NewClient(StubHandler handler, FoodspaceOptions? options = null)
    {
        options ??= Options();
        var http = new HttpClient(handler) { BaseAddress = new Uri(options.BaseUrl!) };
        return new FoodspaceApiClient(http, Microsoft.Extensions.Options.Options.Create(options), NullLogger<FoodspaceApiClient>.Instance);
    }

    private static (CboCollectionForwarder Forwarder, AppDbContext Db, ManualTime Time) NewForwarder(
        IFoodspaceApiClient client, FoodspaceOptions? options = null)
    {
        var db = NewDb();
        var time = new ManualTime();
        var forwarder = new CboCollectionForwarder(
            db, client, Microsoft.Extensions.Options.Options.Create(options ?? Options()), time, NullLogger<CboCollectionForwarder>.Instance);
        return (forwarder, db, time);
    }

    // ── mapper ─────────────────────────────────────────────────────────────────────

    [Fact]
    public void Mapper_CarriesEveryFieldAcross_AndNothingServerOnly()
    {
        var mapped = FoodspaceCboCollectionMapper.ToFoodspace(Collection());

        Assert.Equal("c1", mapped.Id);
        Assert.Equal("cbo-001", mapped.CboId);
        Assert.Equal("Jane Donor", mapped.DonorName);
        Assert.Equal("10:00", mapped.DepartureTime);
        Assert.Equal(new List<bool> { true, false }, mapped.Shots);
        Assert.Equal(-33.9, mapped.Latitude);
        Assert.Equal(111, mapped.CreatedAt);
        var line = Assert.Single(mapped.ProductLines);
        Assert.Equal("42.5", line.Kg);
        Assert.Equal("c1", line.CollectionId);

        var json = JsonSerializer.Serialize(mapped);
        foreach (var serverOnly in new[] { "SyncStatus", "ForwardingStatus", "SyncError", "SyncAttempts", "SubmittedBy" })
            Assert.DoesNotContain(serverOnly, json);
    }

    // ── client ─────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task Client_PostsCamelCaseJson_ToTheCollectionsEndpoint()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.OK));

        var result = await NewClient(handler).SubmitCboCollectionAsync(Collection());

        Assert.Equal(FoodspaceOutcome.Success, result.Outcome);
        var request = Assert.Single(handler.Requests);
        Assert.Equal(HttpMethod.Post, request.Method);
        Assert.Equal("/api/external/cbo-collections", request.RequestUri!.AbsolutePath);
        Assert.Contains("\"donorName\":\"Jane Donor\"", handler.Bodies[0]);
    }

    [Theory]
    [InlineData(500)]
    [InlineData(503)]
    [InlineData(429)]
    [InlineData(408)]
    [InlineData(401)]
    public async Task Client_TreatsOutagesThrottlingAndAuthProblemsAsTransient(int status)
    {
        var handler = new StubHandler(_ => new HttpResponseMessage((HttpStatusCode)status));

        var result = await NewClient(handler).SubmitCboCollectionAsync(Collection());

        Assert.Equal(FoodspaceOutcome.Transient, result.Outcome);
        Assert.Contains(status.ToString(), result.Error);
    }

    [Fact]
    public async Task Client_TreatsARejectedRecordAsPermanent_AndNeverStoresTheResponseBody()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.UnprocessableEntity)
        {
            Content = new StringContent("{\"donorName\":\"Jane Donor is invalid\"}"),
        });

        var result = await NewClient(handler).SubmitCboCollectionAsync(Collection());

        Assert.Equal(FoodspaceOutcome.Permanent, result.Outcome);
        Assert.DoesNotContain("Jane", result.Error);
    }

    [Fact]
    public async Task Client_WhenFoodspaceIsUnreachable_ReturnsTransient_InsteadOfThrowing()
    {
        var handler = new StubHandler(_ => throw new HttpRequestException("connection refused: foodspace.test"));

        var result = await NewClient(handler).SubmitCboCollectionAsync(Collection());

        Assert.Equal(FoodspaceOutcome.Transient, result.Outcome);
    }

    // ── forwarder ──────────────────────────────────────────────────────────────────

    [Fact]
    public async Task Forwarder_Success_MarksTheRecordForwarded()
    {
        var (forwarder, db, _) = NewForwarder(new FakeClient(() => FoodspaceResult.Ok));
        db.CboCollections.Add(Collection());
        await db.SaveChangesAsync();

        Assert.Equal(1, await forwarder.ForwardDueAsync());

        var saved = await db.CboCollections.SingleAsync();
        Assert.Equal(ForwardingStatus.Forwarded, saved.ForwardingStatus);
        Assert.Equal(1, saved.SyncAttempts);
        Assert.Null(saved.NextForwardAttemptAt);
        Assert.Null(saved.SyncError);
    }

    [Fact]
    public async Task Forwarder_FoodspaceOutage_KeepsTheRecord_AsSyncedLocalPendingFoodspace_NotFailed()
    {
        var client = new FakeClient(() => new FoodspaceResult(FoodspaceOutcome.Transient, "Foodspace answered 503"));
        var (forwarder, db, time) = NewForwarder(client);
        db.CboCollections.Add(Collection());
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();

        var saved = await db.CboCollections.Include(c => c.ProductLines).SingleAsync();
        Assert.Equal(ForwardingStatus.SyncedLocalPendingFoodspace, saved.ForwardingStatus);
        Assert.Equal(SyncStatus.Synced, saved.SyncStatus); // still safely received; never marked Failed
        Assert.Single(saved.ProductLines);
        Assert.Equal("Foodspace answered 503", saved.SyncError);
        Assert.Equal(time.Now + TimeSpan.FromSeconds(30), saved.NextForwardAttemptAt);
    }

    [Fact]
    public async Task Forwarder_RetriesOnlyOnceTheBackoffHasElapsed_AndThenRecovers()
    {
        var healthy = false;
        var client = new FakeClient(() => healthy ? FoodspaceResult.Ok : new FoodspaceResult(FoodspaceOutcome.Transient, "down"));
        var (forwarder, db, time) = NewForwarder(client);
        db.CboCollections.Add(Collection());
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();                   // attempt 1: fails
        time.Now += TimeSpan.FromSeconds(10);
        Assert.Equal(0, await forwarder.ForwardDueAsync());  // too early
        healthy = true;
        time.Now += TimeSpan.FromSeconds(30);
        Assert.Equal(1, await forwarder.ForwardDueAsync());  // due, and Foodspace is back

        var saved = await db.CboCollections.SingleAsync();
        Assert.Equal(ForwardingStatus.Forwarded, saved.ForwardingStatus);
        Assert.Equal(2, saved.SyncAttempts);
        Assert.Equal(2, client.Calls);
    }

    [Fact]
    public async Task Forwarder_BackoffDoublesEachAttempt_UpToTheCap()
    {
        var (forwarder, _, _) = NewForwarder(new FakeClient(() => FoodspaceResult.Ok), Options(o => o.MaxDelaySeconds = 100));

        Assert.Equal(TimeSpan.FromSeconds(30), forwarder.BackoffFor(1));
        Assert.Equal(TimeSpan.FromSeconds(60), forwarder.BackoffFor(2));
        Assert.Equal(TimeSpan.FromSeconds(100), forwarder.BackoffFor(3));
    }

    [Fact]
    public async Task Forwarder_StopsRetrying_AfterMaxAttempts_ButKeepsTheRecordVisibleAsPendingFoodspace()
    {
        var client = new FakeClient(() => new FoodspaceResult(FoodspaceOutcome.Transient, "down"));
        var (forwarder, db, time) = NewForwarder(client, Options(o => o.MaxAttempts = 2));
        db.CboCollections.Add(Collection());
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();
        time.Now += TimeSpan.FromHours(2);
        await forwarder.ForwardDueAsync();
        time.Now += TimeSpan.FromHours(2);
        Assert.Equal(0, await forwarder.ForwardDueAsync()); // exhausted: no more automatic attempts

        var saved = await db.CboCollections.SingleAsync();
        Assert.Equal(ForwardingStatus.SyncedLocalPendingFoodspace, saved.ForwardingStatus);
        Assert.Null(saved.NextForwardAttemptAt);
        Assert.Equal(2, client.Calls);
    }

    [Fact]
    public async Task Forwarder_RejectedRecord_IsNotRetriedAutomatically_ButStaysVisibleToAdmins()
    {
        var client = new FakeClient(() => new FoodspaceResult(FoodspaceOutcome.Permanent, "Foodspace answered 422"));
        var (forwarder, db, time) = NewForwarder(client);
        db.CboCollections.Add(Collection());
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();
        time.Now += TimeSpan.FromDays(1);
        Assert.Equal(0, await forwarder.ForwardDueAsync());

        var saved = await db.CboCollections.SingleAsync();
        Assert.Equal(ForwardingStatus.SyncedLocalPendingFoodspace, saved.ForwardingStatus);
        Assert.Equal("Foodspace answered 422", saved.SyncError);
    }

    [Fact]
    public async Task Forwarder_ManualForward_AttemptsEvenWhenExhausted_AndCanRecover()
    {
        var healthy = false;
        var client = new FakeClient(() => healthy ? FoodspaceResult.Ok : new FoodspaceResult(FoodspaceOutcome.Transient, "down"));
        var (forwarder, db, time) = NewForwarder(client, Options(o => o.MaxAttempts = 1));
        db.CboCollections.Add(Collection());
        await db.SaveChangesAsync();
        await forwarder.ForwardDueAsync(); // exhausted straight away
        time.Now += TimeSpan.FromHours(1);

        healthy = true;
        var status = await forwarder.ForwardAsync("c1");

        Assert.Equal(ForwardingStatus.Forwarded, status);
        Assert.Null(await forwarder.ForwardAsync("does-not-exist"));
    }

    [Fact]
    public async Task Forwarder_NeverSendsAlreadyForwardedRecordsAgain()
    {
        var client = new FakeClient(() => FoodspaceResult.Ok);
        var (forwarder, db, _) = NewForwarder(client);
        db.CboCollections.Add(Collection());
        await db.SaveChangesAsync();

        await forwarder.ForwardDueAsync();
        await forwarder.ForwardDueAsync();

        Assert.Equal(1, client.Calls);
    }

    // ── persistence ────────────────────────────────────────────────────────────────

    [Theory]
    [InlineData(ForwardingStatus.Pending, "PENDING")]
    [InlineData(ForwardingStatus.Forwarded, "FORWARDED")]
    [InlineData(ForwardingStatus.SyncedLocalPendingFoodspace, "SYNCED_LOCAL_PENDING_FOODSPACE")]
    public void ForwardingStatus_IsStoredAsUpperSnakeCase(ForwardingStatus value, string expected)
    {
        var converter = new UpperSnakeEnumConverter<ForwardingStatus>();

        Assert.Equal(expected, converter.ConvertToProvider(value));
        Assert.Equal(value, converter.ConvertFromProvider(expected));
    }
}
