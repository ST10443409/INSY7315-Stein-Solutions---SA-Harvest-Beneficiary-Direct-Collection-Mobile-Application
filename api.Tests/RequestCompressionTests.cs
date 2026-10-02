using System.IO.Compression;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;

namespace api.Tests;

/// <summary>
/// The Android app gzips its sync batches (#55: a batch of 50 collections goes from about 59 KB to about 9 KB, which is
/// most of the upload time on 2G). The API decompresses them, and the size limits apply to the decompressed body, so a
/// small compressed "bomb" cannot expand into megabytes (#54).
/// </summary>
public class RequestCompressionTests : IClassFixture<ApiFactory>
{
    private readonly ApiFactory _factory;

    public RequestCompressionTests(ApiFactory factory) => _factory = factory;

    private async Task<HttpClient> ClientFor(string username)
    {
        var client = _factory.CreateClient();
        var login = await client.PostAsJsonAsync("/api/auth/login", new { username, password = ApiFactory.Password });
        login.EnsureSuccessStatusCode();
        var token = (await login.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("token").GetString()!;
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return client;
    }

    private static ByteArrayContent Gzipped(string json)
    {
        using var buffer = new MemoryStream();
        using (var gzip = new GZipStream(buffer, CompressionLevel.Optimal)) gzip.Write(Encoding.UTF8.GetBytes(json));
        var content = new ByteArrayContent(buffer.ToArray());
        content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        content.Headers.ContentEncoding.Add("gzip");
        return content;
    }

    private static object Collection(string id) => new
    {
        id, cboId = "cbo-001", arrivalTime = "09:42", departureTime = "10:26", donorName = "Jane Donor", donorSigned = true,
        cboSigned = true, deliveryNote = "DN-" + id, noteAttached = false, collectNotes = "Collected at the back door.",
        shots = new[] { true, false, false, false }, latitude = (double?)null, longitude = (double?)null,
        createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
        productLines = new[] { new { id = Guid.NewGuid().ToString(), collectionId = id, category = "Fruit", kg = "42.5", notes = (string?)null } },
    };

    private static async Task<string?> ErrorCode(HttpResponseMessage response) =>
        (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("error").GetProperty("code").GetString();

    [Fact]
    public async Task AGzippedCollectionBatch_IsAccepted()
    {
        var ids = Enumerable.Range(0, 50).Select(_ => Guid.NewGuid().ToString()).ToList();
        var json = JsonSerializer.Serialize(new { records = ids.Select(Collection) }, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        var client = await ClientFor("cbo_test_user");

        var response = await client.PostAsync("/api/cbo-collection/sync", Gzipped(json));

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var results = (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("data").GetProperty("results");
        Assert.Equal(ids, results.EnumerateArray().Select(r => r.GetProperty("clientId").GetString()).ToList());
        Assert.All(results.EnumerateArray(), r => Assert.True(r.GetProperty("success").GetBoolean()));
    }

    [Fact]
    public async Task AGzippedDecisionBatch_IsAccepted()
    {
        var id = Guid.NewGuid().ToString();
        var json = JsonSerializer.Serialize(new
        {
            records = new[]
            {
                new { id, foodspaceRecordId = "fs-1001", outcome = "APPROVE", notes = "ok", officerId = "x",
                      decisionTimestamp = 1_700_000_000_000L, createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L },
            },
        });
        var client = await ClientFor("vetting_test_user");

        var response = await client.PostAsync("/api/vetting/sync", Gzipped(json));

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task ASyncBody_ThatExpandsPastTheLimit_IsRefusedWith413()
    {
        // About 5 MB of JSON whitespace (valid, and harmless if it got through) compresses to a few kilobytes.
        var bomb = "{\"records\":[" + new string(' ', 5 * 1024 * 1024) + "]}";
        var client = await ClientFor("cbo_test_user");

        var response = await client.PostAsync("/api/cbo-collection/sync", Gzipped(bomb));

        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, response.StatusCode);
        Assert.Equal("PAYLOAD_TOO_LARGE", await ErrorCode(response));
    }

    [Fact]
    public async Task TheAnonymousLoginEndpoint_AcceptsOnlyASmallBody()
    {
        var bomb = "{\"username\":\"cbo_test_user\",\"password\":\"" + new string('a', 64 * 1024) + "\"}";

        var response = await _factory.CreateClient().PostAsync("/api/auth/login", Gzipped(bomb));

        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, response.StatusCode);
    }
}
