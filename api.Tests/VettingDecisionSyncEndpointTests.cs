using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using api.Data;
using api.Models;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace api.Tests;

/// <summary>POST /api/vetting/sync: the same behaviour as the CBO collection sync, applied to vetting decisions.</summary>
public class VettingDecisionSyncEndpointTests : IClassFixture<ApiFactory>
{
    private const string Path = "/api/vetting/sync";
    private readonly ApiFactory _factory;

    public VettingDecisionSyncEndpointTests(ApiFactory factory) => _factory = factory;

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

    private static Dictionary<string, object?> Decision(string? id = null, Action<Dictionary<string, object?>>? tweak = null)
    {
        var decision = new Dictionary<string, object?>
        {
            ["id"] = id ?? Guid.NewGuid().ToString(),
            ["foodspaceRecordId"] = "fs-1001",
            ["outcome"] = "APPROVE",
            ["notes"] = "Looks good",
            ["officerId"] = "somebody_else", // what a device might claim; the server must ignore it
            ["decisionTimestamp"] = 1_700_000_000_000L,
            ["syncStatus"] = "PENDING",       // device-only
            ["createdAt"] = 1_700_000_000_000L,
            ["updatedAt"] = 1_700_000_000_000L,
        };
        tweak?.Invoke(decision);
        return decision;
    }

    private static async Task<(HttpStatusCode Status, JsonElement Body)> Post(HttpClient client, params object[] records)
    {
        var response = await client.PostAsJsonAsync(Path, new { records });
        return (response.StatusCode, await response.Content.ReadFromJsonAsync<JsonElement>());
    }

    private static JsonElement Results(JsonElement body) => body.GetProperty("data").GetProperty("results");

    private async Task<VettingDecision> Stored(string id)
    {
        using var scope = _factory.Services.CreateScope();
        return await scope.ServiceProvider.GetRequiredService<AppDbContext>().VettingDecisions.AsNoTracking().SingleAsync(d => d.Id == id);
    }

    private async Task<int> RowCount()
    {
        using var scope = _factory.Services.CreateScope();
        return await scope.ServiceProvider.GetRequiredService<AppDbContext>().VettingDecisions.CountAsync();
    }

    // ── access ─────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task WithoutAToken_Is401()
    {
        var (status, _) = await Post(await ClientFor(null), Decision());
        Assert.Equal(HttpStatusCode.Unauthorized, status);
    }

    [Fact]
    public async Task CboCollectionUsers_AreForbidden_AndNothingIsStored()
    {
        var before = await RowCount();

        var (status, _) = await Post(await ClientFor("cbo_test_user"), Decision());

        Assert.Equal(HttpStatusCode.Forbidden, status);
        Assert.Equal(before, await RowCount());
    }

    [Theory]
    [InlineData("vetting_test_user")]
    [InlineData("admin_test_user")]
    public async Task VettingAndAdmin_AreAllowed(string username)
    {
        var (status, body) = await Post(await ClientFor(username), Decision());

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.True(body.GetProperty("success").GetBoolean());
        Assert.True(Results(body)[0].GetProperty("success").GetBoolean());
    }

    // ── what is stored ─────────────────────────────────────────────────────────────

    [Fact]
    public async Task AValidDecision_IsStored_WithEverythingTheDeviceRecorded()
    {
        var id = Guid.NewGuid().ToString();
        var (_, body) = await Post(await ClientFor("vetting_test_user"), Decision(id, d => { d["outcome"] = "REJECT"; d["notes"] = "  no NPO certificate  "; }));

        var result = Results(body)[0];
        Assert.Equal(id, result.GetProperty("clientId").GetString());
        Assert.True(result.GetProperty("success").GetBoolean());
        Assert.False(result.GetProperty("alreadyReceived").GetBoolean());

        var saved = await Stored(id);
        Assert.Equal("fs-1001", saved.FoodspaceRecordId);
        Assert.Equal(DecisionOutcome.Reject, saved.Outcome);
        Assert.Equal("no NPO certificate", saved.Notes);
        Assert.Equal(1_700_000_000_000L, saved.DecisionTimestamp);
        Assert.Equal(1_700_000_000_000L, saved.CreatedAt);
        Assert.Equal(SyncStatus.Synced, saved.SyncStatus);               // it reached this backend...
        Assert.Equal(ForwardingStatus.Pending, saved.ForwardingStatus);   // ...Foodspace has not been told yet
    }

    [Theory]
    [InlineData("APPROVE", DecisionOutcome.Approve)]
    [InlineData("REJECT", DecisionOutcome.Reject)]
    [InlineData("FLAG", DecisionOutcome.Flag)]
    public async Task EachOutcome_IsStored(string wire, DecisionOutcome expected)
    {
        var id = Guid.NewGuid().ToString();
        await Post(await ClientFor("vetting_test_user"), Decision(id, d => d["outcome"] = wire));

        Assert.Equal(expected, (await Stored(id)).Outcome);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    public async Task BlankNotes_AreStoredAsNone(string? notes)
    {
        var id = Guid.NewGuid().ToString();
        await Post(await ClientFor("vetting_test_user"), Decision(id, d => d["notes"] = notes));

        Assert.Null((await Stored(id)).Notes);
    }

    [Fact]
    public async Task TheOfficer_IsTheSignedInUser_NotWhatTheDeviceClaimed()
    {
        var id = Guid.NewGuid().ToString();
        await Post(await ClientFor("vetting_test_user"), Decision(id, d => d["officerId"] = "admin_test_user"));

        Assert.Equal("vetting_test_user", (await Stored(id)).OfficerId);
    }

    [Fact]
    public async Task AMissingOfficer_IsFine_BecauseItComesFromTheToken()
    {
        var id = Guid.NewGuid().ToString();
        var (_, body) = await Post(await ClientFor("vetting_test_user"), Decision(id, d => d.Remove("officerId")));

        Assert.True(Results(body)[0].GetProperty("success").GetBoolean());
        Assert.Equal("vetting_test_user", (await Stored(id)).OfficerId);
    }

    [Fact]
    public async Task TheDevicesSyncStatus_IsIgnored_TheServerSetsItsOwn()
    {
        var id = Guid.NewGuid().ToString();
        await Post(await ClientFor("vetting_test_user"), Decision(id, d =>
        {
            d["syncStatus"] = "FAILED";
            d["retryCount"] = 4;                       // the device's own retry bookkeeping
            d["syncErrorCode"] = "VALIDATION_FAILED";
        }));

        var saved = await Stored(id);
        Assert.Equal(SyncStatus.Synced, saved.SyncStatus);
        Assert.Equal(0, saved.RetryCount);
        Assert.Null(saved.SyncErrorCode);
    }

    [Fact]
    public async Task ADecisionOnABeneficiaryTheServerHasNeverHeardOf_IsStillStored_FoodspaceDecidesLater()
    {
        // Our cached copy of Foodspace's list may be empty or behind; refusing on it would lose honest decisions.
        var id = Guid.NewGuid().ToString();
        var (_, body) = await Post(await ClientFor("vetting_test_user"), Decision(id, d => d["foodspaceRecordId"] = "fs-not-in-any-cache"));

        Assert.True(Results(body)[0].GetProperty("success").GetBoolean());
        Assert.Equal("fs-not-in-any-cache", (await Stored(id)).FoodspaceRecordId);
    }

    [Fact]
    public async Task SeveralDecisionsOnOneBeneficiary_AreAllKept()
    {
        var client = await ClientFor("vetting_test_user");
        var first = Guid.NewGuid().ToString();
        var second = Guid.NewGuid().ToString();
        await Post(client,
            Decision(first, d => { d["foodspaceRecordId"] = "fs-multi"; d["outcome"] = "FLAG"; d["decisionTimestamp"] = 1_700_000_000_000L; }),
            Decision(second, d => { d["foodspaceRecordId"] = "fs-multi"; d["outcome"] = "APPROVE"; d["decisionTimestamp"] = 1_700_000_100_000L; }));

        Assert.Equal(DecisionOutcome.Flag, (await Stored(first)).Outcome);
        Assert.Equal(DecisionOutcome.Approve, (await Stored(second)).Outcome);
    }

    // ── per-record results ─────────────────────────────────────────────────────────

    [Fact]
    public async Task MixedBatch_StoresTheValidOnes_AndReportsTheInvalidOnesIndividually()
    {
        var before = await RowCount();
        var good1 = Decision();
        var bad = Decision(tweak: d => d["outcome"] = "MAYBE");
        var good2 = Decision();

        var (status, body) = await Post(await ClientFor("vetting_test_user"), good1, bad, good2);

        Assert.Equal(HttpStatusCode.OK, status);
        var results = Results(body);
        Assert.Equal(3, results.GetArrayLength());
        Assert.True(results[0].GetProperty("success").GetBoolean());
        Assert.False(results[1].GetProperty("success").GetBoolean());
        Assert.Equal("VALIDATION_FAILED", results[1].GetProperty("errorCode").GetString());
        Assert.False(results[1].GetProperty("retryable").GetBoolean()); // permanent: the app must not retry it
        Assert.Contains("outcome", results[1].GetProperty("error").GetString());
        Assert.True(results[2].GetProperty("success").GetBoolean());
        Assert.Equal(before + 2, await RowCount());
    }

    [Fact]
    public async Task Results_AreInTheSameOrderAsTheRecords()
    {
        var ids = Enumerable.Range(0, 5).Select(_ => Guid.NewGuid().ToString()).ToList();

        var (_, body) = await Post(await ClientFor("vetting_test_user"), ids.Select(id => (object)Decision(id)).ToArray());

        Assert.Equal(ids, Results(body).EnumerateArray().Select(r => r.GetProperty("clientId").GetString()!).ToList());
    }

    [Theory]
    [InlineData("id", "not-a-uuid", "id")]
    [InlineData("id", null, "id")]
    [InlineData("foodspaceRecordId", null, "foodspaceRecordId")]
    [InlineData("foodspaceRecordId", "  ", "foodspaceRecordId")]
    [InlineData("outcome", null, "outcome")]
    [InlineData("outcome", "approve", "outcome")]   // exactly the upper-case wire values
    [InlineData("outcome", "DEFER", "outcome")]
    [InlineData("decisionTimestamp", 0L, "decisionTimestamp")]
    [InlineData("createdAt", 0L, "createdAt")]
    [InlineData("updatedAt", -5L, "updatedAt")]
    public async Task InvalidField_IsReportedByName_AndIsPermanent(string field, object? value, string expectedInError)
    {
        var (_, body) = await Post(await ClientFor("vetting_test_user"), Decision(tweak: d => d[field] = value));

        var result = Results(body)[0];
        Assert.False(result.GetProperty("success").GetBoolean());
        Assert.Equal("VALIDATION_FAILED", result.GetProperty("errorCode").GetString());
        Assert.False(result.GetProperty("retryable").GetBoolean());
        Assert.Contains(expectedInError, result.GetProperty("error").GetString());
    }

    [Fact]
    public async Task NotesAtTheLimit_AreAccepted_AndOneOverIsNot()
    {
        var client = await ClientFor("vetting_test_user");

        var (_, ok) = await Post(client, Decision(tweak: d => d["notes"] = new string('x', 4000)));
        var (_, tooLong) = await Post(client, Decision(tweak: d => d["notes"] = new string('x', 4001)));

        Assert.True(Results(ok)[0].GetProperty("success").GetBoolean());
        Assert.False(Results(tooLong)[0].GetProperty("success").GetBoolean());
        Assert.Contains("notes", Results(tooLong)[0].GetProperty("error").GetString());
    }

    [Fact]
    public async Task EveryProblemWithOneRecord_IsReportedTogether()
    {
        var (_, body) = await Post(await ClientFor("vetting_test_user"),
            Decision(tweak: d => { d["outcome"] = "NOPE"; d["decisionTimestamp"] = 0L; d["foodspaceRecordId"] = null; }));

        var error = Results(body)[0].GetProperty("error").GetString()!;
        Assert.Contains("outcome", error);
        Assert.Contains("decisionTimestamp", error);
        Assert.Contains("foodspaceRecordId", error);
    }

    [Fact]
    public async Task ARecordThatIsJustNull_IsAPerRecordFailure_NotAWholeBatchError()
    {
        var response = await (await ClientFor("vetting_test_user")).PostAsync(Path,
            new StringContent("""{"records":[null]}""", System.Text.Encoding.UTF8, "application/json"));

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.False(Results(body)[0].GetProperty("success").GetBoolean());
    }

    // ── idempotency ────────────────────────────────────────────────────────────────

    [Fact]
    public async Task ResendingTheSameDecision_DoesNotCreateAnother_AndStillSucceeds()
    {
        var client = await ClientFor("vetting_test_user");
        var id = Guid.NewGuid().ToString();
        await Post(client, Decision(id));
        var before = await RowCount();

        var (status, body) = await Post(client, Decision(id));

        Assert.Equal(HttpStatusCode.OK, status);
        var result = Results(body)[0];
        Assert.True(result.GetProperty("success").GetBoolean());
        Assert.True(result.GetProperty("alreadyReceived").GetBoolean());
        Assert.Equal(before, await RowCount());
    }

    [Fact]
    public async Task ARetry_DoesNotOverwriteWhatWasStored_FirstWriteWins()
    {
        var client = await ClientFor("vetting_test_user");
        var id = Guid.NewGuid().ToString();
        await Post(client, Decision(id, d => { d["outcome"] = "APPROVE"; d["notes"] = "original"; }));

        await Post(client, Decision(id, d => { d["outcome"] = "REJECT"; d["notes"] = "changed"; }));

        var saved = await Stored(id);
        Assert.Equal(DecisionOutcome.Approve, saved.Outcome);
        Assert.Equal("original", saved.Notes);
    }

    [Fact]
    public async Task TheSameIdTwiceInOneBatch_IsStoredOnce()
    {
        var id = Guid.NewGuid().ToString();
        var before = await RowCount();

        var (_, body) = await Post(await ClientFor("vetting_test_user"), Decision(id), Decision(id));

        Assert.True(Results(body)[0].GetProperty("success").GetBoolean());
        Assert.True(Results(body)[1].GetProperty("success").GetBoolean());
        Assert.True(Results(body)[1].GetProperty("alreadyReceived").GetBoolean());
        Assert.Equal(before + 1, await RowCount());
    }

    [Fact]
    public async Task AnIdAlreadyStoredForAnotherOfficer_IsRefused_NotReportedAsReceived()
    {
        var id = Guid.NewGuid().ToString();
        await Post(await ClientFor("vetting_test_user"), Decision(id));

        var (_, body) = await Post(await ClientFor("admin_test_user"), Decision(id, d => d["outcome"] = "REJECT"));

        var result = Results(body)[0];
        Assert.False(result.GetProperty("success").GetBoolean());
        Assert.False(result.GetProperty("alreadyReceived").GetBoolean());
        Assert.Equal("VALIDATION_FAILED", result.GetProperty("errorCode").GetString());
        Assert.False(result.GetProperty("retryable").GetBoolean());
        var saved = await Stored(id);
        Assert.Equal("vetting_test_user", saved.OfficerId); // untouched
        Assert.Equal(DecisionOutcome.Approve, saved.Outcome);
    }

    // ── the batch as a whole ───────────────────────────────────────────────────────

    [Fact]
    public async Task EmptyBatch_Is400_WithTheErrorEnvelope()
    {
        var (status, body) = await Post(await ClientFor("vetting_test_user"));

        Assert.Equal(HttpStatusCode.BadRequest, status);
        Assert.False(body.GetProperty("success").GetBoolean());
        Assert.Equal("VALIDATION_FAILED", body.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task MissingRecordsProperty_Is400()
    {
        var response = await (await ClientFor("vetting_test_user")).PostAsync(Path, new StringContent("{}", System.Text.Encoding.UTF8, "application/json"));

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task OversizedBatch_Is400_AndNothingIsStored()
    {
        var before = await RowCount();

        var (status, _) = await Post(await ClientFor("vetting_test_user"), Enumerable.Range(0, 101).Select(_ => (object)Decision()).ToArray());

        Assert.Equal(HttpStatusCode.BadRequest, status);
        Assert.Equal(before, await RowCount());
    }

    [Fact]
    public async Task ABatchOfTheMaximumSize_IsAccepted()
    {
        var (status, body) = await Post(await ClientFor("vetting_test_user"), Enumerable.Range(0, 100).Select(_ => (object)Decision()).ToArray());

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal(100, Results(body).GetArrayLength());
        Assert.All(Results(body).EnumerateArray(), r => Assert.True(r.GetProperty("success").GetBoolean()));
    }

    // ── one convention, not two ────────────────────────────────────────────────────

    [Fact]
    public async Task TheResponse_HasTheSameShapeAsTheCboCollectionSync()
    {
        var client = await ClientFor("admin_test_user");
        var vetting = Results((await Post(client, Decision())).Body)[0];
        var cboRecord = new
        {
            id = Guid.NewGuid().ToString(), cboId = "c", arrivalTime = "09:00", departureTime = (string?)null, donorName = "D",
            donorSigned = true, cboSigned = true, deliveryNote = "n-" + Guid.NewGuid(), noteAttached = false, collectNotes = "",
            shots = new[] { true }, latitude = (double?)null, longitude = (double?)null, createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
            productLines = new[] { new { id = Guid.NewGuid().ToString(), category = "Fruit", kg = "1" } },
        };
        var cbo = await client.PostAsJsonAsync("/api/cbo-collection/sync", new { records = new[] { cboRecord } });
        var cboResult = (await cbo.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("data").GetProperty("results")[0];

        // Everything the app reads for a CBO result is there on a vetting result (the CBO one only adds duplicateOfId).
        var shared = new[] { "clientId", "success", "alreadyReceived", "error", "errorCode", "retryable" };
        Assert.All(shared, key => Assert.True(vetting.TryGetProperty(key, out _), key));
        Assert.Equal(
            cboResult.EnumerateObject().Select(p => p.Name).Where(n => n != "duplicateOfId").OrderBy(n => n),
            vetting.EnumerateObject().Select(p => p.Name).OrderBy(n => n));
    }

    [Fact]
    public async Task ErrorCodes_AreTheOnesTheAppAlreadyHandles()
    {
        var (_, body) = await Post(await ClientFor("vetting_test_user"), Decision(tweak: d => d["outcome"] = "NOPE"));

        Assert.Equal("VALIDATION_FAILED", Results(body)[0].GetProperty("errorCode").GetString());
    }
}
