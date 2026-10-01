using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using api.Data;
using api.Models;
using Microsoft.Extensions.DependencyInjection;

namespace api.Tests;

/// <summary>GET /api/admin/sync-status: Admin only, and the counts by state add up.</summary>
public class AdminSyncStatusEndpointTests : IDisposable
{
    private const string Path = "/api/admin/sync-status";

    // A fresh app (and database) per test, because every test asserts exact counts.
    private readonly ApiFactory _factory = new();

    public void Dispose() => _factory.Dispose();

    // ── helpers ────────────────────────────────────────────────────────────────────

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

    private async Task<JsonElement> Data()
    {
        var response = await (await ClientFor("admin_test_user")).GetAsync(Path);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.True(body.GetProperty("success").GetBoolean());
        return body.GetProperty("data");
    }

    private static int Count(JsonElement form, string name) => form.GetProperty(name).GetInt32();

    private void Seed(Action<AppDbContext> add)
    {
        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        add(db);
        db.SaveChanges();
    }

    private static CboCollection Collection(ForwardingStatus status, DateTimeOffset? nextAttempt = null, string? duplicateOf = null) => new()
    {
        Id = Guid.NewGuid().ToString(),
        CboId = ApiFactory.CboId,
        ArrivalTime = "10:00 AM",
        DonorName = "Donor",
        DeliveryNote = "DN-1",
        CollectNotes = "",
        ForwardingStatus = status,
        NextForwardAttemptAt = nextAttempt,
        DuplicateOfId = duplicateOf,
    };

    private static VettingDecision Decision(ForwardingStatus status, DateTimeOffset? nextAttempt = null) => new()
    {
        Id = Guid.NewGuid().ToString(),
        FoodspaceRecordId = "fs-1",
        OfficerId = "vetting_test_user",
        Outcome = DecisionOutcome.Approve,
        ForwardingStatus = status,
        NextForwardAttemptAt = nextAttempt,
    };

    // ── access ─────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task WithoutAToken_Is401() =>
        Assert.Equal(HttpStatusCode.Unauthorized, (await (await ClientFor(null)).GetAsync(Path)).StatusCode);

    [Theory]
    [InlineData("cbo_test_user")]
    [InlineData("vetting_test_user")]
    public async Task OtherRoles_AreForbidden(string username) =>
        Assert.Equal(HttpStatusCode.Forbidden, (await (await ClientFor(username)).GetAsync(Path)).StatusCode);

    // ── counts ─────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task WithNothingStored_EveryCountIsZero()
    {
        var data = await Data();

        foreach (var form in new[] { "cboCollections", "vettingDecisions" })
            foreach (var name in new[] { "total", "waiting", "retrying", "needsAttention", "forwarded", "duplicates", "superseded" })
                Assert.Equal(0, Count(data.GetProperty(form), name));
    }

    [Fact]
    public async Task Collections_AreCountedIntoOneStateEach()
    {
        var soon = DateTimeOffset.UtcNow.AddMinutes(5);
        Seed(db => db.CboCollections.AddRange(
            Collection(ForwardingStatus.Pending), Collection(ForwardingStatus.Pending),
            Collection(ForwardingStatus.SyncedLocalPendingFoodspace, nextAttempt: soon),               // retry scheduled
            Collection(ForwardingStatus.SyncedLocalPendingFoodspace),                                  // rejected / retries exhausted
            Collection(ForwardingStatus.SyncedLocalPendingFoodspace),
            Collection(ForwardingStatus.Forwarded), Collection(ForwardingStatus.Forwarded), Collection(ForwardingStatus.Forwarded),
            Collection(ForwardingStatus.Pending, duplicateOf: "original-1")));                         // a duplicate is its own state

        var form = (await Data()).GetProperty("cboCollections");

        Assert.Equal(9, Count(form, "total"));
        Assert.Equal(2, Count(form, "waiting"));
        Assert.Equal(1, Count(form, "retrying"));
        Assert.Equal(2, Count(form, "needsAttention"));
        Assert.Equal(3, Count(form, "forwarded"));
        Assert.Equal(1, Count(form, "duplicates"));
        Assert.Equal(0, Count(form, "superseded"));
    }

    [Fact]
    public async Task Decisions_AreCountedIntoOneStateEach_IncludingSuperseded()
    {
        Seed(db => db.VettingDecisions.AddRange(
            Decision(ForwardingStatus.Pending),
            Decision(ForwardingStatus.SyncedLocalPendingFoodspace, nextAttempt: DateTimeOffset.UtcNow.AddMinutes(1)),
            Decision(ForwardingStatus.SyncedLocalPendingFoodspace),
            Decision(ForwardingStatus.Forwarded), Decision(ForwardingStatus.Forwarded),
            Decision(ForwardingStatus.Superseded)));

        var form = (await Data()).GetProperty("vettingDecisions");

        Assert.Equal(6, Count(form, "total"));
        Assert.Equal(1, Count(form, "waiting"));
        Assert.Equal(1, Count(form, "retrying"));
        Assert.Equal(1, Count(form, "needsAttention"));
        Assert.Equal(2, Count(form, "forwarded"));
        Assert.Equal(1, Count(form, "superseded"));
        Assert.Equal(0, Count(form, "duplicates"));
    }

    [Fact]
    public async Task TheStatesAlwaysAddUpToTheTotal()
    {
        Seed(db =>
        {
            db.CboCollections.AddRange(Collection(ForwardingStatus.Pending), Collection(ForwardingStatus.Forwarded),
                Collection(ForwardingStatus.SyncedLocalPendingFoodspace), Collection(ForwardingStatus.Pending, duplicateOf: "x"));
            db.VettingDecisions.AddRange(Decision(ForwardingStatus.Pending), Decision(ForwardingStatus.Superseded),
                Decision(ForwardingStatus.SyncedLocalPendingFoodspace, nextAttempt: DateTimeOffset.UtcNow.AddMinutes(1)));
        });
        var data = await Data();

        foreach (var form in new[] { "cboCollections", "vettingDecisions" })
        {
            var f = data.GetProperty(form);
            var parts = new[] { "waiting", "retrying", "needsAttention", "forwarded", "duplicates", "superseded" }.Sum(n => Count(f, n));
            Assert.Equal(Count(f, "total"), parts);
        }
    }

    [Fact]
    public async Task TheAnswer_CarriesWhenItWasTaken_AndNoRecordData()
    {
        Seed(db => db.CboCollections.Add(Collection(ForwardingStatus.Forwarded)));

        var raw = await (await (await ClientFor("admin_test_user")).GetAsync(Path)).Content.ReadAsStringAsync();

        Assert.Contains("generatedAt", raw);
        Assert.DoesNotContain("Donor", raw);
        Assert.DoesNotContain(ApiFactory.CboId, raw);
    }
}
