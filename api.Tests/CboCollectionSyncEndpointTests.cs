using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using api.Data;
using api.DTOs;
using api.Models;
using api.Services;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace api.Tests;

public class CboCollectionSyncEndpointTests : IClassFixture<ApiFactory>
{
    private const string Path = "/api/cbo-collection/sync";
    private readonly ApiFactory _factory;

    public CboCollectionSyncEndpointTests(ApiFactory factory) => _factory = factory;

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

    private static Dictionary<string, object?> Record(string? id = null, Action<Dictionary<string, object?>>? tweak = null)
    {
        id ??= Guid.NewGuid().ToString();
        var record = new Dictionary<string, object?>
        {
            ["id"] = id,
            ["cboId"] = "cbo-001",
            ["arrivalTime"] = "09:42",
            ["departureTime"] = "10:26",
            ["donorName"] = "Jane Donor",
            ["donorSigned"] = true,
            ["cboSigned"] = true,
            ["deliveryNote"] = "DN-" + id, // unique per record, so unrelated tests never look like duplicates of each other
            ["noteAttached"] = false,
            ["collectNotes"] = "",
            ["shots"] = new[] { true, false, false, false },
            ["latitude"] = null,
            ["longitude"] = null,
            ["createdAt"] = 1_700_000_000_000L,
            ["updatedAt"] = 1_700_000_000_000L,
            ["productLines"] = new[]
            {
                new Dictionary<string, object?>
                {
                    ["id"] = Guid.NewGuid().ToString(), ["collectionId"] = id, ["category"] = "Fruit", ["kg"] = "42.5", ["notes"] = null,
                },
            },
        };
        tweak?.Invoke(record);
        return record;
    }

    private static async Task<(HttpStatusCode Status, JsonElement Body)> Post(HttpClient client, params object[] records)
    {
        var response = await client.PostAsJsonAsync(Path, new { records });
        return (response.StatusCode, await response.Content.ReadFromJsonAsync<JsonElement>());
    }

    private static JsonElement Results(JsonElement body) => body.GetProperty("data").GetProperty("results");

    private async Task<int> RowCount()
    {
        using var scope = _factory.Services.CreateScope();
        return await scope.ServiceProvider.GetRequiredService<AppDbContext>().CboCollections.CountAsync();
    }

    // ── access ─────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task WithoutAToken_Is401()
    {
        var (status, _) = await Post(await ClientFor(null), Record());
        Assert.Equal(HttpStatusCode.Unauthorized, status);
    }

    [Fact]
    public async Task VettingOfficers_AreForbidden()
    {
        var (status, _) = await Post(await ClientFor("vetting_test_user"), Record());
        Assert.Equal(HttpStatusCode.Forbidden, status);
    }

    [Theory]
    [InlineData("cbo_test_user")]
    [InlineData("admin_test_user")]
    public async Task CboCollectionAndAdmin_AreAllowed(string username)
    {
        var (status, body) = await Post(await ClientFor(username), Record());

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.True(body.GetProperty("success").GetBoolean());
    }

    // ── per-record results ─────────────────────────────────────────────────────────

    [Fact]
    public async Task ValidRecord_IsStored_WithItsProductLines_AndTheSubmitter()
    {
        var id = Guid.NewGuid().ToString();
        var (_, body) = await Post(await ClientFor("cbo_test_user"), Record(id));

        var result = Results(body)[0];
        Assert.Equal(id, result.GetProperty("clientId").GetString());
        Assert.True(result.GetProperty("success").GetBoolean());
        Assert.False(result.GetProperty("alreadyReceived").GetBoolean());

        using var scope = _factory.Services.CreateScope();
        var saved = await scope.ServiceProvider.GetRequiredService<AppDbContext>().CboCollections
            .Include(c => c.ProductLines).SingleAsync(c => c.Id == id);
        Assert.Equal("cbo_test_user", saved.SubmittedBy);
        Assert.Equal(SyncStatus.Synced, saved.SyncStatus);
        Assert.Equal(ForwardingStatus.Pending, saved.ForwardingStatus);
        Assert.Equal("42.5", Assert.Single(saved.ProductLines).Kg);
    }

    [Fact]
    public async Task MixedBatch_PersistsTheValidOnes_AndReportsTheInvalidOnesIndividually()
    {
        var before = await RowCount();
        var good1 = Record();
        var bad = Record(tweak: r => r["donorName"] = "");
        var good2 = Record();
        var badKg = Record(tweak: r => ((Dictionary<string, object?>[])r["productLines"]!)[0]["kg"] = "abc");

        var (status, body) = await Post(await ClientFor("cbo_test_user"), good1, bad, good2, badKg);

        Assert.Equal(HttpStatusCode.OK, status);
        var results = Results(body);
        Assert.Equal(4, results.GetArrayLength());
        Assert.Equal(new[] { true, false, true, false }, results.EnumerateArray().Select(r => r.GetProperty("success").GetBoolean()));

        var rejected = results[1];
        Assert.Equal("VALIDATION_FAILED", rejected.GetProperty("errorCode").GetString());
        Assert.False(rejected.GetProperty("retryable").GetBoolean()); // permanent: the app must not retry it
        Assert.Contains("donorName", rejected.GetProperty("error").GetString());
        Assert.Contains("kg", results[3].GetProperty("error").GetString());

        Assert.Equal(before + 2, await RowCount());
    }

    [Fact]
    public async Task Results_AreInTheSameOrderAsTheRecords()
    {
        var ids = Enumerable.Range(0, 5).Select(_ => Guid.NewGuid().ToString()).ToArray();

        var (_, body) = await Post(await ClientFor("cbo_test_user"), ids.Select(i => (object)Record(i)).ToArray());

        Assert.Equal(ids, Results(body).EnumerateArray().Select(r => r.GetProperty("clientId").GetString()));
    }

    // ── idempotency ────────────────────────────────────────────────────────────────

    [Fact]
    public async Task ResendingTheSameRecord_DoesNotCreateADuplicate_AndStillSucceeds()
    {
        var client = await ClientFor("cbo_test_user");
        var id = Guid.NewGuid().ToString();
        await Post(client, Record(id));
        var before = await RowCount();

        var (_, body) = await Post(client, Record(id));

        var result = Results(body)[0];
        Assert.True(result.GetProperty("success").GetBoolean());
        Assert.True(result.GetProperty("alreadyReceived").GetBoolean());
        Assert.Equal(before, await RowCount());
    }

    [Fact]
    public async Task ARetry_DoesNotOverwriteTheStoredRecord_FirstWriteWins()
    {
        var client = await ClientFor("cbo_test_user");
        var id = Guid.NewGuid().ToString();
        await Post(client, Record(id));

        await Post(client, Record(id, r => r["donorName"] = "Someone Else"));

        using var scope = _factory.Services.CreateScope();
        var saved = await scope.ServiceProvider.GetRequiredService<AppDbContext>().CboCollections.SingleAsync(c => c.Id == id);
        Assert.Equal("Jane Donor", saved.DonorName);
    }

    [Fact]
    public async Task TheSameIdTwiceInOneBatch_IsStoredOnce()
    {
        var before = await RowCount();
        var id = Guid.NewGuid().ToString();

        var (_, body) = await Post(await ClientFor("cbo_test_user"), Record(id), Record(id));

        Assert.All(Results(body).EnumerateArray(), r => Assert.True(r.GetProperty("success").GetBoolean()));
        Assert.Equal(before + 1, await RowCount());
    }

    // ── validation rules ───────────────────────────────────────────────────────────

    [Theory]
    [InlineData("id", "not-a-uuid", "id")]
    [InlineData("cboId", null, "cboId")]
    [InlineData("arrivalTime", "  ", "arrivalTime")]
    [InlineData("shots", null, "shots")]
    [InlineData("latitude", 123.0, "latitude")]
    [InlineData("createdAt", 0L, "createdAt")]
    public async Task InvalidField_IsReportedByName(string field, object? value, string expectedInError)
    {
        var (_, body) = await Post(await ClientFor("cbo_test_user"), Record(tweak: r => r[field] = value));

        var result = Results(body)[0];
        Assert.False(result.GetProperty("success").GetBoolean());
        Assert.Contains(expectedInError, result.GetProperty("error").GetString());
    }

    [Fact]
    public async Task RecordWithNoProductLines_IsRejected()
    {
        var (_, body) = await Post(await ClientFor("cbo_test_user"),
            Record(tweak: r => r["productLines"] = Array.Empty<object>()));

        Assert.Contains("product line", Results(body)[0].GetProperty("error").GetString());
    }

    [Theory]
    [InlineData("0")]
    [InlineData("-5")]
    [InlineData("1e3")]
    [InlineData("12.3456")]
    public async Task BadKg_IsRejected(string kg)
    {
        var (_, body) = await Post(await ClientFor("cbo_test_user"),
            Record(tweak: r => ((Dictionary<string, object?>[])r["productLines"]!)[0]["kg"] = kg));

        Assert.False(Results(body)[0].GetProperty("success").GetBoolean());
    }

    // ── whole-request problems ─────────────────────────────────────────────────────

    [Fact]
    public async Task EmptyBatch_Is400_WithTheErrorEnvelope()
    {
        var client = await ClientFor("cbo_test_user");
        var response = await client.PostAsJsonAsync(Path, new { records = Array.Empty<object>() });
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.False(body.GetProperty("success").GetBoolean());
        Assert.Equal("VALIDATION_FAILED", body.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task OversizedBatch_Is400()
    {
        var records = Enumerable.Range(0, 101).Select(_ => (object)Record()).ToArray();

        var (status, _) = await Post(await ClientFor("cbo_test_user"), records);

        Assert.Equal(HttpStatusCode.BadRequest, status);
    }

    [Fact]
    public async Task ABatchOfTwenty_WithThreeBadRecords_ReportsTwentyResults_SeventeenSuccesses()
    {
        var records = Enumerable.Range(0, 20)
            .Select(i => (object)Record(tweak: r => { if (i is 2 or 9 or 15) r["donorName"] = null; }))
            .ToArray();

        var (_, body) = await Post(await ClientFor("cbo_test_user"), records);

        var results = Results(body);
        Assert.Equal(20, results.GetArrayLength());
        Assert.Equal(17, results.EnumerateArray().Count(r => r.GetProperty("success").GetBoolean()));
    }

    // ── #38: duplicates (different client id, same real-world collection) ───────────

    private static Dictionary<string, object?> SameVisit(string? id = null, Action<Dictionary<string, object?>>? tweak = null) =>
        Record(id, r =>
        {
            r["cboId"] = "cbo-dup";
            r["donorName"] = "Fresh Fields Wholesale";
            r["deliveryNote"] = "DN-4821";
            r["createdAt"] = 1_700_000_000_000L;
            tweak?.Invoke(r);
        });

    private async Task<CboCollection> Load(string id)
    {
        using var scope = _factory.Services.CreateScope();
        return await scope.ServiceProvider.GetRequiredService<AppDbContext>().CboCollections
            .Include(c => c.ProductLines).AsNoTracking().SingleAsync(c => c.Id == id);
    }

    [Fact]
    public async Task DifferentIdSameCollection_IsReportedAsDuplicateDetected_NotSuccessAndNotAValidationError()
    {
        var client = await ClientFor("cbo_test_user");
        var originalId = Guid.NewGuid().ToString();
        var duplicateId = Guid.NewGuid().ToString();
        await Post(client, SameVisit(originalId, r => r["cboId"] = "cbo-dup-1"));

        var (status, body) = await Post(client, SameVisit(duplicateId, r => r["cboId"] = "cbo-dup-1"));

        Assert.Equal(HttpStatusCode.OK, status);
        var result = Results(body)[0];
        Assert.False(result.GetProperty("success").GetBoolean());
        Assert.Equal("DUPLICATE_DETECTED", result.GetProperty("errorCode").GetString());
        Assert.Equal(originalId, result.GetProperty("duplicateOfId").GetString());
        Assert.False(result.GetProperty("retryable").GetBoolean());
        Assert.False(result.GetProperty("alreadyReceived").GetBoolean());
    }

    [Fact]
    public async Task TheOriginal_IsPreservedUntouched_AndTheDuplicateIsKeptForReview()
    {
        var client = await ClientFor("cbo_test_user");
        var originalId = Guid.NewGuid().ToString();
        var duplicateId = Guid.NewGuid().ToString();
        await Post(client, SameVisit(originalId, r => { r["cboId"] = "cbo-dup-2"; r["collectNotes"] = "original notes"; }));

        await Post(client, SameVisit(duplicateId, r => { r["cboId"] = "cbo-dup-2"; r["collectNotes"] = "different notes"; }));

        var original = await Load(originalId);
        Assert.Equal("original notes", original.CollectNotes);
        Assert.Null(original.DuplicateOfId);
        var duplicate = await Load(duplicateId);
        Assert.Equal(originalId, duplicate.DuplicateOfId);
        Assert.Equal("different notes", duplicate.CollectNotes);
        Assert.Single(duplicate.ProductLines);
    }

    [Fact]
    public async Task ResendingAStoredDuplicate_GetsTheSameDuplicateAnswer_NotASuccess()
    {
        var client = await ClientFor("cbo_test_user");
        var duplicateId = Guid.NewGuid().ToString();
        await Post(client, SameVisit(tweak: r => r["cboId"] = "cbo-dup-3"));
        await Post(client, SameVisit(duplicateId, r => r["cboId"] = "cbo-dup-3"));
        var before = await RowCount();

        var (_, body) = await Post(client, SameVisit(duplicateId, r => r["cboId"] = "cbo-dup-3"));

        Assert.Equal("DUPLICATE_DETECTED", Results(body)[0].GetProperty("errorCode").GetString());
        Assert.Equal(before, await RowCount());
    }

    [Fact]
    public async Task RetryOfTheOriginal_IsStillAnIdempotentSuccess_NotADuplicateOfItself()
    {
        var client = await ClientFor("cbo_test_user");
        var originalId = Guid.NewGuid().ToString();
        await Post(client, SameVisit(originalId, r => r["cboId"] = "cbo-dup-4"));

        var (_, body) = await Post(client, SameVisit(originalId, r => r["cboId"] = "cbo-dup-4"));

        var result = Results(body)[0];
        Assert.True(result.GetProperty("success").GetBoolean());
        Assert.True(result.GetProperty("alreadyReceived").GetBoolean());
    }

    [Fact]
    public async Task TwoSubmissionsOfTheSameCollectionInOneBatch_FirstIsOriginal_SecondIsFlagged()
    {
        var first = Guid.NewGuid().ToString();
        var second = Guid.NewGuid().ToString();

        var (_, body) = await Post(await ClientFor("cbo_test_user"),
            SameVisit(first, r => r["cboId"] = "cbo-dup-5"),
            SameVisit(second, r => r["cboId"] = "cbo-dup-5"),
            Record());

        var results = Results(body);
        Assert.True(results[0].GetProperty("success").GetBoolean());
        Assert.Equal("DUPLICATE_DETECTED", results[1].GetProperty("errorCode").GetString());
        Assert.Equal(first, results[1].GetProperty("duplicateOfId").GetString());
        Assert.True(results[2].GetProperty("success").GetBoolean());
    }

    [Theory]
    [InlineData("deliveryNote", "DN-9999")]            // a second pick-up with its own delivery note
    [InlineData("donorName", "Another Donor")]
    [InlineData("cboId", "cbo-other")]
    [InlineData("createdAt", 1_700_200_000_000L)]       // a different day
    public async Task ADifferentCboDonorDateOrDeliveryNote_IsNotADuplicate(string field, object value)
    {
        var client = await ClientFor("cbo_test_user");
        var scope = "cbo-neg-" + field;
        await Post(client, SameVisit(tweak: r => r["cboId"] = scope));

        var (_, body) = await Post(client, SameVisit(tweak: r =>
        {
            r["cboId"] = scope;
            r[field] = field == "cboId" ? scope + "-other" : value;
        }));

        Assert.True(Results(body)[0].GetProperty("success").GetBoolean());
    }

    [Fact]
    public async Task Duplicates_AreNotForwardedToFoodspace()
    {
        var client = await ClientFor("cbo_test_user");
        var originalId = Guid.NewGuid().ToString();
        var duplicateId = Guid.NewGuid().ToString();
        await Post(client, SameVisit(originalId, r => r["cboId"] = "cbo-fwd"));
        await Post(client, SameVisit(duplicateId, r => r["cboId"] = "cbo-fwd"));

        using var scope = _factory.Services.CreateScope();
        var due = await scope.ServiceProvider.GetRequiredService<AppDbContext>().CboCollections
            .Where(c => c.DuplicateOfId == null && c.ForwardingStatus == ForwardingStatus.Pending)
            .Select(c => c.Id).ToListAsync();
        Assert.Contains(originalId, due);
        Assert.DoesNotContain(duplicateId, due);
    }

    // ── the duplicate key rule ──────────────────────────────────────────────────────

    private static CboCollectionSyncItemDto Item(string cbo = "c", string donor = "D", string note = "N", long createdAt = 1_700_000_000_000L) =>
        new() { CboId = cbo, DonorName = donor, DeliveryNote = note, CreatedAt = createdAt };

    [Fact]
    public void DuplicateKey_IgnoresCaseAndWhitespace()
    {
        Assert.Equal(
            CboCollectionDuplicateKey.For(Item(donor: "Fresh Fields", note: "DN-1")),
            CboCollectionDuplicateKey.For(Item(donor: "  fresh   FIELDS ", note: "dn-1")));
    }

    [Fact]
    public void DuplicateKey_UsesTheSouthAfricanCalendarDay()
    {
        // 21:30 UTC is 23:30 in South Africa; 22:30 UTC is already 00:30 the next day there.
        var lateEvening = new DateTimeOffset(2023, 11, 14, 21, 30, 0, TimeSpan.Zero).ToUnixTimeMilliseconds();
        var justAfterMidnight = new DateTimeOffset(2023, 11, 14, 22, 30, 0, TimeSpan.Zero).ToUnixTimeMilliseconds();

        Assert.NotEqual(
            CboCollectionDuplicateKey.For(Item(createdAt: lateEvening)),
            CboCollectionDuplicateKey.For(Item(createdAt: justAfterMidnight)));
    }

    [Fact]
    public void DuplicateKey_IsAHashThatDoesNotContainTheDonorName()
    {
        var key = CboCollectionDuplicateKey.For(Item(donor: "Jane Donor"));

        Assert.Equal(64, key.Length);
        Assert.DoesNotContain("jane", key);
    }
}
