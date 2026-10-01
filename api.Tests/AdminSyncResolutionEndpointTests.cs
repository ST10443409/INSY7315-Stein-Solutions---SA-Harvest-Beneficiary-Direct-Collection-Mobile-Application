using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using api.Data;
using api.Models;
using api.Services.Foodspace;
using Microsoft.AspNetCore.Hosting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace api.Tests;

/// <summary>
/// The failed-sync tooling (#50): GET /api/admin/sync-status/attention, GET /{id}, POST /{id}/retry, POST /{id}/dismiss.
/// Admin only, shows why a record is stuck, retries it, dismisses it, and records who did what.
/// </summary>
public class AdminSyncResolutionEndpointTests : IDisposable
{
    private const string Base = "/api/admin/sync-status";

    // ── fakes ──────────────────────────────────────────────────────────────────────

    private sealed class FakeFoodspace : IFoodspaceApiClient
    {
        public int Calls;
        public FoodspaceResult Answer { get; set; } = FoodspaceResult.Ok;

        public Task<FoodspaceResult> SubmitCboCollectionAsync(CboCollection collection, CancellationToken ct = default)
        {
            Interlocked.Increment(ref Calls);
            return Task.FromResult(Answer);
        }

        public Task<FoodspaceResult> SubmitVettingDecisionAsync(VettingDecision decision, CancellationToken ct = default)
        {
            Interlocked.Increment(ref Calls);
            return Task.FromResult(Answer);
        }

        public Task<FoodspaceBeneficiariesResult> GetBeneficiariesAsync(CancellationToken ct = default) =>
            throw new NotSupportedException("not used here");
    }

    private sealed class ResolutionFactory : ApiFactory
    {
        public FakeFoodspace Foodspace { get; } = new();

        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            base.ConfigureWebHost(builder);
            builder.ConfigureServices(services =>
            {
                services.RemoveAll<IFoodspaceApiClient>();
                services.AddSingleton<IFoodspaceApiClient>(Foodspace);
            });
        }
    }

    // A fresh app and database per test: the tests assert exact lists and counts.
    private readonly ResolutionFactory _factory = new();

    public void Dispose() => _factory.Dispose();

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static readonly DateTimeOffset T0 = new(2026, 10, 1, 8, 0, 0, TimeSpan.Zero);

    private async Task<HttpClient> ClientFor(string? username)
    {
        var client = _factory.CreateClient();
        if (username is null) return client;
        var login = await client.PostAsJsonAsync("/api/auth/login", new { username, password = ApiFactory.Password });
        login.EnsureSuccessStatusCode();
        var token = (await login.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("token").GetString()!;
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return client;
    }

    private async Task<(HttpStatusCode Status, JsonElement Body)> Send(HttpMethod method, string path, object? body = null, string user = "admin_test_user")
    {
        var client = await ClientFor(user);
        var request = new HttpRequestMessage(method, path);
        if (body is not null) request.Content = JsonContent.Create(body);
        var response = await client.SendAsync(request);
        return (response.StatusCode, await response.Content.ReadFromJsonAsync<JsonElement>());
    }

    private Task<(HttpStatusCode Status, JsonElement Body)> Get(string path, string user = "admin_test_user") => Send(HttpMethod.Get, path, user: user);
    private Task<(HttpStatusCode Status, JsonElement Body)> Post(string path, object? body = null, string user = "admin_test_user") => Send(HttpMethod.Post, path, body ?? new { }, user);

    private static JsonElement Data(JsonElement body) => body.GetProperty("data");

    private void Seed(Action<AppDbContext> add)
    {
        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        add(db);
        db.SaveChanges();
    }

    private T Read<T>(Func<AppDbContext, T> read)
    {
        using var scope = _factory.Services.CreateScope();
        return read(scope.ServiceProvider.GetRequiredService<AppDbContext>());
    }

    private static CboCollection Collection(
        string id, ForwardingStatus status = ForwardingStatus.SyncedLocalPendingFoodspace, DateTimeOffset? next = null,
        string? duplicateOf = null, string? error = "Foodspace answered 422 (Unprocessable Entity)", int attempts = 3, int minutes = 0) => new()
    {
        Id = id,
        CboId = ApiFactory.CboId,
        ArrivalTime = "10:00 AM",
        DonorName = $"Donor {id}",
        DeliveryNote = $"DN-{id}",
        CollectNotes = "",
        SubmittedBy = "cbo_test_user",
        ForwardingStatus = status,
        NextForwardAttemptAt = next,
        DuplicateOfId = duplicateOf,
        SyncError = error,
        SyncAttempts = attempts,
        LastSyncAttemptAt = T0.AddMinutes(minutes + 1),
        ReceivedAt = T0.AddMinutes(minutes),
    };

    private static VettingDecision Decision(
        string id, ForwardingStatus status = ForwardingStatus.SyncedLocalPendingFoodspace, DateTimeOffset? next = null,
        string beneficiary = "fs-1", long timestamp = 1_700_000_000_000L, string? error = "Foodspace answered 404 (Not Found)", int attempts = 2, int minutes = 0) => new()
    {
        Id = id,
        FoodspaceRecordId = beneficiary,
        OfficerId = "vetting_test_user",
        Outcome = DecisionOutcome.Approve,
        DecisionTimestamp = timestamp,
        CreatedAt = timestamp,
        ForwardingStatus = status,
        NextForwardAttemptAt = next,
        SyncError = error,
        SyncAttempts = attempts,
        LastSyncAttemptAt = T0.AddMinutes(minutes + 1),
        ReceivedAt = T0.AddMinutes(minutes),
    };

    private static string Ids(JsonElement items) => string.Join(",", items.EnumerateArray().Select(i => i.GetProperty("id").GetString()));

    // ── access ─────────────────────────────────────────────────────────────────────

    [Theory]
    [InlineData("GET", "/attention")]
    [InlineData("GET", "/some-id")]
    [InlineData("POST", "/some-id/retry")]
    [InlineData("POST", "/some-id/dismiss")]
    public async Task WithoutAToken_Is401(string method, string suffix)
    {
        var (status, _) = await Send(new HttpMethod(method), Base + suffix, new { reason = "x" }, user: null!);
        Assert.Equal(HttpStatusCode.Unauthorized, status);
    }

    [Theory]
    [InlineData("cbo_test_user", "GET", "/attention")]
    [InlineData("cbo_test_user", "POST", "/c1/retry")]
    [InlineData("vetting_test_user", "GET", "/attention")]
    [InlineData("vetting_test_user", "GET", "/c1")]
    [InlineData("vetting_test_user", "POST", "/c1/retry")]
    [InlineData("vetting_test_user", "POST", "/c1/dismiss")]
    public async Task OtherRoles_AreForbidden_AndNothingHappens(string user, string method, string suffix)
    {
        Seed(db => db.CboCollections.Add(Collection("c1")));

        var (status, _) = await Send(new HttpMethod(method), Base + suffix, new { reason = "x" }, user);

        Assert.Equal(HttpStatusCode.Forbidden, status);
        Assert.Equal(0, _factory.Foodspace.Calls);
        Assert.Equal(ForwardingStatus.SyncedLocalPendingFoodspace, Read(db => db.CboCollections.Single().ForwardingStatus));
        Assert.Equal(0, Read(db => db.AdminActions.Count()));
    }

    // ── the list ───────────────────────────────────────────────────────────────────

    [Fact]
    public async Task TheList_HoldsOnlyWhatNeedsAnAdmin_FromBothForms_OldestFirst()
    {
        var soon = DateTimeOffset.UtcNow.AddMinutes(5);
        Seed(db =>
        {
            db.CboCollections.AddRange(
                Collection("rejected-c", minutes: 10),
                Collection("held-dup", ForwardingStatus.Pending, duplicateOf: "orig", error: null, attempts: 0, minutes: 5),
                Collection("retrying-c", next: soon),
                Collection("waiting-c", ForwardingStatus.Pending, error: null, attempts: 0),
                Collection("sent-c", ForwardingStatus.Forwarded, error: null),
                Collection("dismissed-c", ForwardingStatus.Dismissed));
            db.VettingDecisions.AddRange(
                Decision("rejected-d", minutes: 1),
                Decision("retrying-d", next: soon),
                Decision("old-d", ForwardingStatus.Superseded),
                Decision("sent-d", ForwardingStatus.Forwarded, error: null));
        });

        var (status, body) = await Get(Base + "/attention");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.True(body.GetProperty("success").GetBoolean());
        Assert.Equal("rejected-d,held-dup,rejected-c", Ids(Data(body).GetProperty("items")));
        Assert.Equal(3, Data(body).GetProperty("totalCount").GetInt32());
        Assert.False(Data(body).GetProperty("hasMore").GetBoolean());
    }

    [Fact]
    public async Task EachItem_ShowsTheError_TheAttempts_AndWhatKindOfRecordItIs()
    {
        Seed(db =>
        {
            db.CboCollections.Add(Collection("c1", error: "Foodspace answered 422 (Unprocessable Entity)", attempts: 5));
            db.VettingDecisions.Add(Decision("d1", error: "Foodspace answered 404 (Not Found)", attempts: 2, minutes: 1));
        });

        var items = Data((await Get(Base + "/attention")).Body).GetProperty("items").EnumerateArray().ToList();

        var c = items.Single(i => i.GetProperty("id").GetString() == "c1");
        Assert.Equal("CBO_COLLECTION", c.GetProperty("form").GetString());
        Assert.Equal("NEEDS_ATTENTION", c.GetProperty("state").GetString());
        Assert.Equal("Foodspace answered 422 (Unprocessable Entity)", c.GetProperty("error").GetString());
        Assert.Equal(5, c.GetProperty("syncAttempts").GetInt32());
        Assert.Equal("cbo_test_user", c.GetProperty("submittedBy").GetString());
        Assert.Contains("Donor c1", c.GetProperty("label").GetString());

        var d = items.Single(i => i.GetProperty("id").GetString() == "d1");
        Assert.Equal("VETTING_DECISION", d.GetProperty("form").GetString());
        Assert.Equal("Foodspace answered 404 (Not Found)", d.GetProperty("error").GetString());
        Assert.Equal("vetting_test_user", d.GetProperty("submittedBy").GetString());
        Assert.Contains("fs-1", d.GetProperty("label").GetString());
    }

    [Fact]
    public async Task TheList_CanBeNarrowedToOneForm()
    {
        Seed(db =>
        {
            db.CboCollections.Add(Collection("c1"));
            db.VettingDecisions.Add(Decision("d1"));
        });

        Assert.Equal("c1", Ids(Data((await Get(Base + "/attention?form=CBO_COLLECTION")).Body).GetProperty("items")));
        Assert.Equal("d1", Ids(Data((await Get(Base + "/attention?form=vetting_decision")).Body).GetProperty("items")));
    }

    [Theory]
    [InlineData("form=nonsense")]
    [InlineData("page=0")]
    [InlineData("pageSize=0")]
    [InlineData("pageSize=101")]
    public async Task ABadQuery_Is400(string query)
    {
        var (status, body) = await Get($"{Base}/attention?{query}");

        Assert.Equal(HttpStatusCode.BadRequest, status);
        Assert.False(body.GetProperty("success").GetBoolean());
    }

    [Fact]
    public async Task TheList_IsPaged_AcrossBothForms()
    {
        Seed(db =>
        {
            db.CboCollections.AddRange(Collection("c1", minutes: 1), Collection("c3", minutes: 3));
            db.VettingDecisions.AddRange(Decision("d2", minutes: 2), Decision("d4", minutes: 4));
        });

        var first = Data((await Get(Base + "/attention?pageSize=3")).Body);
        var second = Data((await Get(Base + "/attention?pageSize=3&page=2")).Body);

        Assert.Equal("c1,d2,c3", Ids(first.GetProperty("items")));
        Assert.True(first.GetProperty("hasMore").GetBoolean());
        Assert.Equal("d4", Ids(second.GetProperty("items")));
        Assert.False(second.GetProperty("hasMore").GetBoolean());
        Assert.Equal(4, second.GetProperty("totalCount").GetInt32());
    }

    [Fact]
    public async Task AnEmptyList_IsNotAnError()
    {
        var (status, body) = await Get(Base + "/attention");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Empty(Data(body).GetProperty("items").EnumerateArray());
        Assert.Equal(0, Data(body).GetProperty("totalCount").GetInt32());
    }

    // ── one record ─────────────────────────────────────────────────────────────────

    [Fact]
    public async Task TheDetail_ShowsTheErrorDetail_BeforeAnythingIsRetried()
    {
        Seed(db => db.CboCollections.Add(Collection("c1", error: "Foodspace answered 422 (Unprocessable Entity)", attempts: 4)));

        var (status, body) = await Get(Base + "/c1");

        Assert.Equal(HttpStatusCode.OK, status);
        var d = Data(body);
        Assert.Equal("NEEDS_ATTENTION", d.GetProperty("state").GetString());
        Assert.Equal("Foodspace answered 422 (Unprocessable Entity)", d.GetProperty("error").GetString());
        Assert.Equal(4, d.GetProperty("syncAttempts").GetInt32());
        Assert.True(d.GetProperty("canRetry").GetBoolean());
        Assert.True(d.GetProperty("canDismiss").GetBoolean());
        Assert.Empty(d.GetProperty("history").EnumerateArray());
        Assert.Equal(0, _factory.Foodspace.Calls); // looking is not acting
    }

    [Fact]
    public async Task ASuspectedDuplicate_ShowsTheRecordItWasMatchedWith()
    {
        Seed(db => db.CboCollections.AddRange(
            Collection("orig", ForwardingStatus.Forwarded, error: null, attempts: 1),
            Collection("dup", ForwardingStatus.Pending, duplicateOf: "orig", error: null, attempts: 0, minutes: 5)));

        var d = Data((await Get(Base + "/dup")).Body);

        Assert.Equal("DUPLICATE_HELD", d.GetProperty("state").GetString());
        var original = d.GetProperty("duplicateOf");
        Assert.Equal("orig", original.GetProperty("id").GetString());
        Assert.Equal("FORWARDED", original.GetProperty("state").GetString());
        Assert.Contains("Donor orig", original.GetProperty("label").GetString());
        Assert.True(d.GetProperty("canRetry").GetBoolean());
        Assert.True(d.GetProperty("canDismiss").GetBoolean());
    }

    [Fact]
    public async Task AForwardedRecord_CannotBeRetriedOrDismissed()
    {
        Seed(db => db.CboCollections.Add(Collection("c1", ForwardingStatus.Forwarded, error: null)));

        var d = Data((await Get(Base + "/c1")).Body);

        Assert.False(d.GetProperty("canRetry").GetBoolean());
        Assert.False(d.GetProperty("canDismiss").GetBoolean());
    }

    [Fact]
    public async Task AnUnknownId_Is404()
    {
        var (status, body) = await Get(Base + "/nope");

        Assert.Equal(HttpStatusCode.NotFound, status);
        Assert.Equal("NOT_FOUND", body.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task AnIdUsedByBothForms_NeedsTheFormSaying()
    {
        Seed(db =>
        {
            db.CboCollections.Add(Collection("same"));
            db.VettingDecisions.Add(Decision("same"));
        });

        var (ambiguous, _) = await Get(Base + "/same");
        var (collection, c) = await Get(Base + "/same?form=CBO_COLLECTION");
        var (decision, d) = await Get(Base + "/same?form=VETTING_DECISION");

        Assert.Equal(HttpStatusCode.Conflict, ambiguous);
        Assert.Equal(HttpStatusCode.OK, collection);
        Assert.Equal("CBO_COLLECTION", Data(c).GetProperty("form").GetString());
        Assert.Equal(HttpStatusCode.OK, decision);
        Assert.Equal("VETTING_DECISION", Data(d).GetProperty("form").GetString());
    }

    // ── retry ──────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task ARetryThatWorks_ForwardsTheRecord_ClearsTheError_AndShowsInTheMonitor()
    {
        Seed(db => db.CboCollections.Add(Collection("c1", attempts: 8)));

        var (status, body) = await Post(Base + "/c1/retry");

        Assert.Equal(HttpStatusCode.OK, status);
        var data = Data(body);
        Assert.Equal("NEEDS_ATTENTION", data.GetProperty("previousState").GetString());
        Assert.Equal("FORWARDED", data.GetProperty("record").GetProperty("state").GetString());
        Assert.Equal(1, _factory.Foodspace.Calls);

        var stored = Read(db => db.CboCollections.Single());
        Assert.Equal(ForwardingStatus.Forwarded, stored.ForwardingStatus);
        Assert.Null(stored.SyncError);
        Assert.Equal(1, stored.SyncAttempts); // started again from zero, then this one attempt

        // ...and #49's monitor sees it.
        var counts = Data((await Get("/api/admin/sync-status")).Body).GetProperty("cboCollections");
        Assert.Equal(1, counts.GetProperty("forwarded").GetInt32());
        Assert.Equal(0, counts.GetProperty("needsAttention").GetInt32());
    }

    [Fact]
    public async Task ARetry_StartsTheAttemptCountAgain_SoARetryThatFailsAgainGetsAFreshRetryBudget()
    {
        _factory.Foodspace.Answer = new FoodspaceResult(FoodspaceOutcome.Transient, "Foodspace could not be reached");
        Seed(db => db.CboCollections.Add(Collection("c1", attempts: 8)));

        var (_, body) = await Post(Base + "/c1/retry");

        var record = Data(body).GetProperty("record");
        Assert.Equal("RETRYING", record.GetProperty("state").GetString()); // not stuck at "retries used up"
        Assert.Equal(1, record.GetProperty("syncAttempts").GetInt32());
        Assert.NotEqual(JsonValueKind.Null, record.GetProperty("nextAttemptAt").ValueKind);
        Assert.Equal("Foodspace could not be reached", record.GetProperty("error").GetString());
    }

    [Fact]
    public async Task ARetryThatIsRejectedAgain_StaysNeedingAttention_WithTheNewError()
    {
        _factory.Foodspace.Answer = new FoodspaceResult(FoodspaceOutcome.Permanent, "Foodspace answered 400 (Bad Request)");
        Seed(db => db.CboCollections.Add(Collection("c1")));

        var (status, body) = await Post(Base + "/c1/retry");

        Assert.Equal(HttpStatusCode.OK, status); // the retry itself worked; the answer is in the record
        var record = Data(body).GetProperty("record");
        Assert.Equal("NEEDS_ATTENTION", record.GetProperty("state").GetString());
        Assert.Equal("Foodspace answered 400 (Bad Request)", record.GetProperty("error").GetString());
    }

    [Fact]
    public async Task ARetry_OfADecision_Works()
    {
        Seed(db => db.VettingDecisions.Add(Decision("d1")));

        var (_, body) = await Post(Base + "/d1/retry");

        Assert.Equal("FORWARDED", Data(body).GetProperty("record").GetProperty("state").GetString());
        Assert.Equal(ForwardingStatus.Forwarded, Read(db => db.VettingDecisions.Single().ForwardingStatus));
    }

    [Fact]
    public async Task ARetry_OfASupersededDecision_IsRefused_AndNothingIsSent()
    {
        Seed(db => db.VettingDecisions.Add(Decision("d1", ForwardingStatus.Superseded, error: null)));

        var (status, body) = await Post(Base + "/d1/retry");

        Assert.Equal(HttpStatusCode.Conflict, status);
        Assert.Equal("CONFLICT", body.GetProperty("error").GetProperty("code").GetString());
        Assert.Equal(0, _factory.Foodspace.Calls);
    }

    [Fact]
    public async Task ARetry_OfADecisionThatHasSinceBeenReplaced_IsNotSent_AndEndsSuperseded()
    {
        // d-old is stuck, but the officer has since decided again on the same beneficiary.
        Seed(db => db.VettingDecisions.AddRange(
            Decision("d-old", beneficiary: "fs-9", timestamp: 1_700_000_000_000L),
            Decision("d-new", ForwardingStatus.Forwarded, beneficiary: "fs-9", timestamp: 1_700_000_100_000L, error: null)));

        var (status, body) = await Post(Base + "/d-old/retry");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal("SUPERSEDED", Data(body).GetProperty("record").GetProperty("state").GetString());
        Assert.Equal(0, _factory.Foodspace.Calls);
    }

    [Fact]
    public async Task ARetry_OfARecordFoodspaceAlreadyHas_IsRefused()
    {
        Seed(db => db.CboCollections.Add(Collection("c1", ForwardingStatus.Forwarded, error: null)));

        var (status, _) = await Post(Base + "/c1/retry");

        Assert.Equal(HttpStatusCode.Conflict, status);
        Assert.Equal(0, _factory.Foodspace.Calls);
        Assert.Equal(0, Read(db => db.AdminActions.Count()));
    }

    [Fact]
    public async Task ARetry_OfAnUnknownRecord_Is404()
    {
        var (status, _) = await Post(Base + "/nope/retry");

        Assert.Equal(HttpStatusCode.NotFound, status);
        Assert.Equal(0, Read(db => db.AdminActions.Count()));
    }

    [Fact]
    public async Task ARetry_OfAHeldDuplicate_ReleasesIt_AndLeavesTheOriginalAlone()
    {
        Seed(db => db.CboCollections.AddRange(
            Collection("orig", ForwardingStatus.Forwarded, error: null, attempts: 1),
            Collection("dup", ForwardingStatus.Pending, duplicateOf: "orig", error: null, attempts: 0, minutes: 5)));

        var (_, body) = await Post(Base + "/dup/retry");

        var data = Data(body);
        Assert.Equal("DUPLICATE_HELD", data.GetProperty("previousState").GetString());
        Assert.Equal("FORWARDED", data.GetProperty("record").GetProperty("state").GetString());
        Assert.Equal(1, _factory.Foodspace.Calls);
        Assert.Equal(1, Read(db => db.CboCollections.Single(c => c.Id == "orig").SyncAttempts));

        // Released, so it is no longer counted as a held duplicate.
        var counts = Data((await Get("/api/admin/sync-status")).Body).GetProperty("cboCollections");
        Assert.Equal(0, counts.GetProperty("duplicates").GetInt32());
        Assert.Equal(2, counts.GetProperty("forwarded").GetInt32());
    }

    // ── dismiss ────────────────────────────────────────────────────────────────────

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    public async Task ADismissal_NeedsAReason(string? reason)
    {
        Seed(db => db.CboCollections.Add(Collection("c1")));

        var (status, body) = await Post(Base + "/c1/dismiss", new { reason });

        Assert.Equal(HttpStatusCode.BadRequest, status);
        Assert.Equal("VALIDATION_FAILED", body.GetProperty("error").GetProperty("code").GetString());
        Assert.Equal(ForwardingStatus.SyncedLocalPendingFoodspace, Read(db => db.CboCollections.Single().ForwardingStatus));
    }

    [Fact]
    public async Task ADismissal_WithAnOverlongReason_Is400()
    {
        Seed(db => db.CboCollections.Add(Collection("c1")));

        var (status, _) = await Post(Base + "/c1/dismiss", new { reason = new string('x', 501) });

        Assert.Equal(HttpStatusCode.BadRequest, status);
    }

    [Fact]
    public async Task ADismissedRecord_IsNeverSent_AndLeavesTheList_AndShowsInTheMonitor()
    {
        Seed(db => db.CboCollections.Add(Collection("c1")));

        var (status, body) = await Post(Base + "/c1/dismiss", new { reason = "  Handled directly with Foodspace  " });

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal("NEEDS_ATTENTION", Data(body).GetProperty("previousState").GetString());
        Assert.Equal("DISMISSED", Data(body).GetProperty("record").GetProperty("state").GetString());
        Assert.False(Data(body).GetProperty("record").GetProperty("canDismiss").GetBoolean());

        Assert.Empty(Data((await Get(Base + "/attention")).Body).GetProperty("items").EnumerateArray());
        var counts = Data((await Get("/api/admin/sync-status")).Body).GetProperty("cboCollections");
        Assert.Equal(1, counts.GetProperty("dismissed").GetInt32());
        Assert.Equal(0, counts.GetProperty("needsAttention").GetInt32());
        Assert.Equal(1, counts.GetProperty("total").GetInt32());

        // The background loop never touches it.
        using var scope = _factory.Services.CreateScope();
        Assert.Equal(0, await scope.ServiceProvider.GetRequiredService<ICboCollectionForwarder>().ForwardDueAsync());
        Assert.Equal(0, _factory.Foodspace.Calls);
    }

    [Fact]
    public async Task AHeldDuplicate_CanBeDismissed_AsAConfirmedDuplicate()
    {
        Seed(db => db.CboCollections.AddRange(
            Collection("orig", ForwardingStatus.Forwarded, error: null),
            Collection("dup", ForwardingStatus.Pending, duplicateOf: "orig", error: null, attempts: 0, minutes: 5)));

        var (status, body) = await Post(Base + "/dup/dismiss", new { reason = "Same delivery note, entered twice" });

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal("DUPLICATE_HELD", Data(body).GetProperty("previousState").GetString());
        Assert.Equal("DISMISSED", Data(body).GetProperty("record").GetProperty("state").GetString());
        Assert.Equal(0, _factory.Foodspace.Calls);
    }

    [Fact]
    public async Task ARejectedDecision_CanBeDismissed()
    {
        Seed(db => db.VettingDecisions.Add(Decision("d1")));

        var (status, body) = await Post(Base + "/d1/dismiss", new { reason = "Foodspace removed this beneficiary" });

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal("DISMISSED", Data(body).GetProperty("record").GetProperty("state").GetString());
    }

    [Theory]
    [InlineData(ForwardingStatus.Pending)]
    [InlineData(ForwardingStatus.Forwarded)]
    [InlineData(ForwardingStatus.Dismissed)]
    public async Task OnlyARecordThatNeedsAnAdmin_CanBeDismissed(ForwardingStatus status)
    {
        Seed(db => db.CboCollections.Add(Collection("c1", status, error: null, attempts: 0)));

        var (code, _) = await Post(Base + "/c1/dismiss", new { reason = "x" });

        Assert.Equal(HttpStatusCode.Conflict, code);
        Assert.Equal(status, Read(db => db.CboCollections.Single().ForwardingStatus));
        Assert.Equal(0, Read(db => db.AdminActions.Count()));
    }

    [Fact]
    public async Task ARecordThatIsStillRetrying_CannotBeDismissed()
    {
        Seed(db => db.CboCollections.Add(Collection("c1", next: DateTimeOffset.UtcNow.AddMinutes(5))));

        var (code, _) = await Post(Base + "/c1/dismiss", new { reason = "x" });

        Assert.Equal(HttpStatusCode.Conflict, code);
    }

    [Fact]
    public async Task ADismissedRecord_CanBeBroughtBack_WithARetry()
    {
        Seed(db => db.CboCollections.Add(Collection("c1")));
        await Post(Base + "/c1/dismiss", new { reason = "Thought it was handled" });

        var (status, body) = await Post(Base + "/c1/retry");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal("DISMISSED", Data(body).GetProperty("previousState").GetString());
        Assert.Equal("FORWARDED", Data(body).GetProperty("record").GetProperty("state").GetString());
    }

    // ── accountability ─────────────────────────────────────────────────────────────

    [Fact]
    public async Task ARetry_IsRecorded_WithWhoDidWhatToWhichRecordAndWhen()
    {
        Seed(db => db.CboCollections.Add(Collection("c1", attempts: 6)));
        var before = DateTimeOffset.UtcNow.AddSeconds(-1);

        await Post(Base + "/c1/retry");

        var action = Read(db => db.AdminActions.Single());
        Assert.Equal("admin_test_user", action.Admin);
        Assert.Equal(AdminActionType.Retry, action.Action);
        Assert.Equal(SyncForm.CboCollection, action.Form);
        Assert.Equal("c1", action.RecordId);
        Assert.Equal(ForwardingStatus.SyncedLocalPendingFoodspace, action.PreviousStatus);
        Assert.Equal(ForwardingStatus.Forwarded, action.ResultStatus);
        Assert.Equal(6, action.PreviousAttempts);
        Assert.Null(action.Reason);
        Assert.True(action.OccurredAt >= before && action.OccurredAt <= DateTimeOffset.UtcNow.AddSeconds(1));
    }

    [Fact]
    public async Task ADismissal_IsRecorded_WithTheReason()
    {
        Seed(db => db.VettingDecisions.Add(Decision("d1")));

        await Post(Base + "/d1/dismiss", new { reason = "Beneficiary withdrawn" });

        var action = Read(db => db.AdminActions.Single());
        Assert.Equal("admin_test_user", action.Admin);
        Assert.Equal(AdminActionType.Dismiss, action.Action);
        Assert.Equal(SyncForm.VettingDecision, action.Form);
        Assert.Equal("Beneficiary withdrawn", action.Reason);
        Assert.Equal(ForwardingStatus.Dismissed, action.ResultStatus);
    }

    [Fact]
    public async Task TheHistory_ShowsWhatHasBeenDone_NewestFirst()
    {
        _factory.Foodspace.Answer = new FoodspaceResult(FoodspaceOutcome.Permanent, "Foodspace answered 400 (Bad Request)");
        Seed(db => db.CboCollections.Add(Collection("c1")));
        await Post(Base + "/c1/retry");
        await Post(Base + "/c1/dismiss", new { reason = "Giving up" });

        var history = Data((await Get(Base + "/c1")).Body).GetProperty("history").EnumerateArray().ToList();

        Assert.Equal(2, history.Count);
        Assert.Equal("DISMISS", history[0].GetProperty("action").GetString());
        Assert.Equal("Giving up", history[0].GetProperty("reason").GetString());
        Assert.Equal("DISMISSED", history[0].GetProperty("resultStatus").GetString());
        Assert.Equal("RETRY", history[1].GetProperty("action").GetString());
        Assert.Equal("admin_test_user", history[1].GetProperty("admin").GetString());
        Assert.Equal("SYNCED_LOCAL_PENDING_FOODSPACE", history[1].GetProperty("resultStatus").GetString());
    }

    [Fact]
    public async Task AnAction_OnAnotherRecord_IsNotInThisRecordsHistory()
    {
        Seed(db => db.CboCollections.AddRange(Collection("c1"), Collection("c2", minutes: 1)));
        await Post(Base + "/c1/retry");

        var history = Data((await Get(Base + "/c2")).Body).GetProperty("history");

        Assert.Empty(history.EnumerateArray());
    }
}
