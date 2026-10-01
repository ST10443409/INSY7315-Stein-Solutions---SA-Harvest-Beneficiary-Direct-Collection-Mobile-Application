using System.IO.Compression;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.RegularExpressions;
using api.Data;
using api.Models;
using api.Services.Foodspace;
using Microsoft.AspNetCore.Hosting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit.Abstractions;

namespace api.Tests;

/// <summary>GET /api/vetting/records: access, the lean field set, paging, filtering, and the Foodspace cache.</summary>
public class VettingRecordsEndpointTests
{
    private const string Path = "/api/vetting/records";
    private readonly ITestOutputHelper _output;

    public VettingRecordsEndpointTests(ITestOutputHelper output) => _output = output;

    // ── fakes ──────────────────────────────────────────────────────────────────────

    private sealed class ManualTime : TimeProvider
    {
        // Starts at the real time, because the JWT middleware checks tokens against the real clock.
        public DateTimeOffset Now { get; set; } = DateTimeOffset.UtcNow;
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private sealed class FakeFoodspace : IFoodspaceApiClient
    {
        private int _calls;
        public int Calls => _calls;
        public Func<FoodspaceBeneficiariesResult> Respond { get; set; } = () => new(FoodspaceOutcome.Success, Array.Empty<FoodspaceBeneficiaryRecord>());
        public TimeSpan Delay { get; set; }

        public void Serve(params FoodspaceBeneficiaryRecord[] records) => Respond = () => new(FoodspaceOutcome.Success, records);
        public void Down() => Respond = () => new(FoodspaceOutcome.Transient, Array.Empty<FoodspaceBeneficiaryRecord>(), Error: "Foodspace answered 503 (Service Unavailable)");

        public async Task<FoodspaceBeneficiariesResult> GetBeneficiariesAsync(CancellationToken cancellationToken = default)
        {
            Interlocked.Increment(ref _calls);
            if (Delay > TimeSpan.Zero) await Task.Delay(Delay, cancellationToken);
            return Respond();
        }

        public Task<FoodspaceResult> SubmitVettingDecisionAsync(VettingDecision decision, CancellationToken cancellationToken = default) =>
            Task.FromResult(FoodspaceResult.Ok);

        public Task<FoodspaceResult> SubmitCboCollectionAsync(CboCollection collection, CancellationToken cancellationToken = default) =>
            Task.FromResult(FoodspaceResult.Ok);
    }

    private sealed class VettingFactory : ApiFactory
    {
        public FakeFoodspace Foodspace { get; } = new();
        public ManualTime Time { get; } = new();

        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            base.ConfigureWebHost(builder);
            builder.ConfigureServices(services =>
            {
                services.RemoveAll<IFoodspaceApiClient>();
                services.AddSingleton<IFoodspaceApiClient>(Foodspace);
                services.RemoveAll<TimeProvider>();
                services.AddSingleton<TimeProvider>(Time);
            });
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static FoodspaceBeneficiaryRecord Rec(string id, string province = "Gauteng", string? legalName = null) => new()
    {
        Id = id,
        LegalName = legalName ?? $"Organisation {id}",
        ContactName = "Nomsa Dlamini",
        ContactEmail = "nomsa@example.org",
        ContactPhone = "+27 82 000 0001",
        Website = "https://example.org",
        Address = "12 Vilakazi Street",
        Address2 = "Orlando West",
        Province = province,
        What3words = "filled.count.soap",
        CoreBusiness = "Daily hot meals for children and the elderly",
        TargetPopulation = new() { "Children", "Elderly" },
        Programmes = "Feeding scheme, after-school homework club",
        DistributionChannel = "On-site kitchen",
        FullTimeFemales = 3, FullTimeMales = 1, Volunteers = 12,
        RegisteredNpo = true, NpoCertificate = "NPO-123-456", RegisteredDsd = true, PboCertificate = "PBO-930-0001",
        Race = new() { "African" }, Gender = new() { "Female", "Male" }, AgeGroups = new() { "0-12", "60+" },
        FeedingFrequency = "Daily", TotalServed = 220, FemalesServed = 130, MalesServed = 90, AfricanServed = 220,
        RelianceOnSaHarvest = "High", TransportCapacity = "One bakkie",
        MealsProvided = new() { "Lunch", "Dinner" }, DaysOfWeek = new() { "Mon", "Tue", "Wed", "Thu", "Fri" },
        LastDateFed = 1_790_000_000_000,
        FoodStorage = new() { "Fridge", "Dry store" },
        KitchenCleanliness = true, AccessToWater = true, Toilets = true, PestFree = true,
        InfrastructureChecks = new() { "Gas safety certificate" },
        EaseOfAccess = true, ParkingSecurity = true, PoliceProximity = "Orlando SAPS, 1.2 km",
        AdditionalComments = "Well organised; strong volunteer base.",
        HasSla = true, HasConsent = true, HasPolicy = true,
    };

    private static FoodspaceBeneficiaryRecord[] Many(int count, string province = "Gauteng") =>
        Enumerable.Range(1, count).Select(i => Rec($"fs-{i:D4}", province)).ToArray();

    private static async Task<HttpClient> ClientFor(VettingFactory factory, string? username, bool gzip = false)
    {
        var client = factory.CreateClient();
        if (username is null) return client;
        var login = await client.PostAsJsonAsync("/api/auth/login", new { username, password = ApiFactory.Password });
        login.EnsureSuccessStatusCode();
        var token = (await login.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("token").GetString()!;
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        // After logging in, so only the call under test is compressed.
        if (gzip) client.DefaultRequestHeaders.AcceptEncoding.Add(new StringWithQualityHeaderValue("gzip"));
        return client;
    }

    private static async Task<(HttpStatusCode Status, JsonElement Body)> Get(HttpClient client, string query = "")
    {
        var response = await client.GetAsync(Path + query);
        return (response.StatusCode, await response.Content.ReadFromJsonAsync<JsonElement>());
    }

    private static JsonElement Data(JsonElement body) => body.GetProperty("data");

    private static List<string> Ids(JsonElement body) =>
        Data(body).GetProperty("items").EnumerateArray().Select(i => i.GetProperty("id").GetString()!).ToList();

    private static string RoomEntityFile()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            var candidate = System.IO.Path.Combine(dir.FullName, "client", "app", "src", "main", "java", "com", "example", "client",
                "data", "local", "entity", "FoodspaceBeneficiaryRecord.kt");
            if (File.Exists(candidate)) return candidate;
            dir = dir.Parent;
        }
        throw new FileNotFoundException("Could not locate FoodspaceBeneficiaryRecord.kt above the test binaries.");
    }

    // ── access ─────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task WithoutAToken_Is401()
    {
        await using var factory = new VettingFactory();
        var (status, _) = await Get(await ClientFor(factory, null));
        Assert.Equal(HttpStatusCode.Unauthorized, status);
        Assert.Equal(0, factory.Foodspace.Calls);
    }

    [Fact]
    public async Task ACboCollectionToken_Is403_AndFoodspaceIsNotAsked()
    {
        await using var factory = new VettingFactory();
        var (status, body) = await Get(await ClientFor(factory, "cbo_test_user"));

        Assert.Equal(HttpStatusCode.Forbidden, status);
        Assert.Equal("FORBIDDEN", body.GetProperty("error").GetProperty("code").GetString());
        Assert.Equal(0, factory.Foodspace.Calls);
    }

    [Theory]
    [InlineData("vetting_test_user")]
    [InlineData("admin_test_user")]
    public async Task VettingAndAdmin_AreAllowed(string username)
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(3));

        var (status, body) = await Get(await ClientFor(factory, username));

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.True(body.GetProperty("success").GetBoolean());
        Assert.Equal(3, Ids(body).Count);
    }

    // ── the lean field set ─────────────────────────────────────────────────────────

    [Fact]
    public async Task EachRecord_HasExactlyTheFieldsOfTheAndroidEntity_NothingMoreOrLess()
    {
        var kotlin = Regex.Replace(File.ReadAllText(RoomEntityFile()), @"//.*", "");
        var body = Regex.Match(kotlin, @"data class FoodspaceBeneficiaryRecord\((?<b>.*?)\n\)", RegexOptions.Singleline).Groups["b"].Value;
        var roomFields = Regex.Matches(body, @"val\s+(?<n>\w+)\s*:").Select(m => m.Groups["n"].Value).ToHashSet();
        Assert.True(roomFields.Count > 40, "The Room entity should have been parsed.");

        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Rec("fs-1"));
        var (_, response) = await Get(await ClientFor(factory, "vetting_test_user"));

        var served = Data(response).GetProperty("items")[0].EnumerateObject().Select(p => p.Name).ToHashSet();
        Assert.Equal(roomFields.OrderBy(x => x), served.OrderBy(x => x));
    }

    [Fact]
    public async Task ServerOnlyBookkeeping_IsNeverSentToTheApp()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Rec("fs-1"));

        var (_, response) = await Get(await ClientFor(factory, "vetting_test_user"));

        Assert.False(Data(response).GetProperty("items")[0].TryGetProperty("fetchedAt", out _));
    }

    // ── paging ─────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task Paging_WalksTheWholeSet_WithoutGapsOrRepeats_InStableOrder()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(120));
        var client = await ClientFor(factory, "vetting_test_user");

        var (_, p1) = await Get(client, "?page=1&pageSize=50");
        var (_, p2) = await Get(client, "?page=2&pageSize=50");
        var (_, p3) = await Get(client, "?page=3&pageSize=50");

        Assert.Equal(50, Ids(p1).Count);
        Assert.Equal(50, Ids(p2).Count);
        Assert.Equal(20, Ids(p3).Count);
        Assert.True(Data(p1).GetProperty("hasMore").GetBoolean());
        Assert.True(Data(p2).GetProperty("hasMore").GetBoolean());
        Assert.False(Data(p3).GetProperty("hasMore").GetBoolean());
        Assert.All(new[] { p1, p2, p3 }, p => Assert.Equal(120, Data(p).GetProperty("totalCount").GetInt32()));

        var all = Ids(p1).Concat(Ids(p2)).Concat(Ids(p3)).ToList();
        Assert.Equal(120, all.Distinct().Count());
        Assert.Equal(all.OrderBy(x => x, StringComparer.Ordinal), all);
    }

    [Fact]
    public async Task ThePageSizeDefaultsToFifty_AndAnExactMultipleHasNoPhantomNextPage()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(50));

        var (_, body) = await Get(await ClientFor(factory, "vetting_test_user"));

        Assert.Equal(50, Ids(body).Count);
        Assert.Equal(50, Data(body).GetProperty("pageSize").GetInt32());
        Assert.False(Data(body).GetProperty("hasMore").GetBoolean());
    }

    [Fact]
    public async Task APageBeyondTheEnd_IsEmpty_NotAnError()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(5));

        var (status, body) = await Get(await ClientFor(factory, "vetting_test_user"), "?page=9&pageSize=10");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Empty(Ids(body));
        Assert.False(Data(body).GetProperty("hasMore").GetBoolean());
        Assert.Equal(5, Data(body).GetProperty("totalCount").GetInt32());
    }

    [Theory]
    [InlineData("?page=0", "page")]
    [InlineData("?page=-1", "page")]
    [InlineData("?pageSize=0", "pageSize")]
    [InlineData("?pageSize=101", "pageSize")]
    public async Task BadPagingParameters_Are400_NamingTheField(string query, string field)
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(3));

        var (status, body) = await Get(await ClientFor(factory, "vetting_test_user"), query);

        Assert.Equal(HttpStatusCode.BadRequest, status);
        var error = body.GetProperty("error");
        Assert.Equal("VALIDATION_FAILED", error.GetProperty("code").GetString());
        Assert.True(error.GetProperty("details").TryGetProperty(field, out _));
    }

    [Fact]
    public async Task ThePageSizeCap_IsAllowedExactly()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(150));

        var (status, body) = await Get(await ClientFor(factory, "vetting_test_user"), "?pageSize=100");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal(100, Ids(body).Count);
    }

    // ── filtering ──────────────────────────────────────────────────────────────────

    [Fact]
    public async Task ProvinceFilter_IsCaseInsensitive_AndTheTotalReflectsIt()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Rec("a", "Gauteng"), Rec("b", "Western Cape"), Rec("c", "Gauteng"), Rec("d", "KwaZulu-Natal"));

        var (_, body) = await Get(await ClientFor(factory, "vetting_test_user"), "?province=gauTENG");

        Assert.Equal(new[] { "a", "c" }, Ids(body));
        Assert.Equal(2, Data(body).GetProperty("totalCount").GetInt32());
    }

    [Fact]
    public async Task AProvinceWithNoRecords_GivesAnEmptyPage()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Rec("a", "Gauteng"));

        var (status, body) = await Get(await ClientFor(factory, "vetting_test_user"), "?province=Limpopo");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Empty(Ids(body));
    }

    // ── the cache ──────────────────────────────────────────────────────────────────

    [Fact]
    public async Task Foodspace_IsAskedOnce_WhateverTheNumberOfPagesOrRequests()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(120));
        var client = await ClientFor(factory, "vetting_test_user");

        await Get(client, "?page=1&pageSize=50");
        await Get(client, "?page=2&pageSize=50");
        await Get(client, "?page=3&pageSize=50");

        Assert.Equal(1, factory.Foodspace.Calls);
    }

    [Fact]
    public async Task ABurstOfSimultaneousRequests_AsksFoodspaceOnlyOnce()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(20));
        factory.Foodspace.Delay = TimeSpan.FromMilliseconds(150);
        var clients = await Task.WhenAll(Enumerable.Range(0, 6).Select(_ => ClientFor(factory, "vetting_test_user")));

        var results = await Task.WhenAll(clients.Select(c => Get(c)));

        Assert.All(results, r => Assert.Equal(HttpStatusCode.OK, r.Status));
        Assert.All(results, r => Assert.Equal(20, Ids(r.Body).Count));
        Assert.Equal(1, factory.Foodspace.Calls);
    }

    [Fact]
    public async Task OnceTheCacheExpires_FoodspaceIsAskedAgain_AndTheListIsReplaced()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Rec("a", legalName: "Old Name"), Rec("gone"));
        var client = await ClientFor(factory, "vetting_test_user");
        await Get(client);

        factory.Time.Now += TimeSpan.FromMinutes(16);
        factory.Foodspace.Serve(Rec("a", legalName: "New Name"), Rec("new"));
        var (_, body) = await Get(client);

        Assert.Equal(2, factory.Foodspace.Calls);
        Assert.Equal(new[] { "a", "new" }, Ids(body)); // "gone" is no longer in Foodspace's list
        Assert.Equal("New Name", Data(body).GetProperty("items")[0].GetProperty("legalName").GetString());
        Assert.False(Data(body).GetProperty("stale").GetBoolean());
    }

    [Fact]
    public async Task WithinTheCacheWindow_ANewFoodspaceListIsNotYetVisible()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Rec("a"));
        var client = await ClientFor(factory, "vetting_test_user");
        await Get(client);

        factory.Time.Now += TimeSpan.FromMinutes(5);
        factory.Foodspace.Serve(Rec("a"), Rec("b"));
        var (_, body) = await Get(client);

        Assert.Equal(new[] { "a" }, Ids(body));
        Assert.Equal(1, factory.Foodspace.Calls);
    }

    [Fact]
    public async Task WhenFoodspaceGoesDown_TheLastCachedListIsStillServed_FlaggedStale()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(7));
        var client = await ClientFor(factory, "vetting_test_user");
        var (_, fresh) = await Get(client);
        var fetchedAt = Data(fresh).GetProperty("fetchedAt").GetDateTimeOffset();

        factory.Time.Now += TimeSpan.FromMinutes(30);
        factory.Foodspace.Down();
        var (status, stale) = await Get(client);

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal(7, Ids(stale).Count);
        Assert.True(Data(stale).GetProperty("stale").GetBoolean());
        Assert.Equal(fetchedAt, Data(stale).GetProperty("fetchedAt").GetDateTimeOffset()); // tells the app how old it is
    }

    [Fact]
    public async Task WhenFoodspaceRecovers_TheNextRequestRefreshes_AndIsNoLongerStale()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(2));
        var client = await ClientFor(factory, "vetting_test_user");
        await Get(client);
        factory.Time.Now += TimeSpan.FromMinutes(30);
        factory.Foodspace.Down();
        await Get(client);

        factory.Foodspace.Serve(Many(4));
        var (_, body) = await Get(client);

        Assert.Equal(4, Ids(body).Count);
        Assert.False(Data(body).GetProperty("stale").GetBoolean());
    }

    [Fact]
    public async Task WhenNothingIsCachedAndFoodspaceIsDown_ItIsA503_WithAnActionableMessage()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Down();

        var (status, body) = await Get(await ClientFor(factory, "vetting_test_user"));

        Assert.Equal(HttpStatusCode.ServiceUnavailable, status);
        Assert.False(body.GetProperty("success").GetBoolean());
        Assert.Equal("FOODSPACE_UNAVAILABLE", body.GetProperty("error").GetProperty("code").GetString());
        // Safe to show: no upstream status text, hostnames or exception detail.
        var message = body.GetProperty("error").GetProperty("message").GetString()!;
        Assert.DoesNotContain("503", message);
        Assert.DoesNotContain("Service Unavailable", message);
    }

    [Fact]
    public async Task AnEmptyFoodspaceList_IsAValidList_NotAnError()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(); // nobody is awaiting vetting

        var (status, body) = await Get(await ClientFor(factory, "vetting_test_user"));

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Empty(Ids(body));
    }

    [Fact]
    public async Task IfEverythingFoodspaceSentIsUnreadable_TheCachedListIsKept_NotWipedWithNothing()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(5));
        var client = await ClientFor(factory, "vetting_test_user");
        await Get(client);

        factory.Time.Now += TimeSpan.FromMinutes(30);
        factory.Foodspace.Respond = () => new(FoodspaceOutcome.Success, Array.Empty<FoodspaceBeneficiaryRecord>(), Skipped: 5);
        var (_, body) = await Get(client);

        Assert.Equal(5, Ids(body).Count);
        Assert.True(Data(body).GetProperty("stale").GetBoolean());
    }

    [Fact]
    public async Task TheSameIdTwiceInFoodspacesList_IsStoredOnce()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Rec("a", legalName: "First"), Rec("a", legalName: "Second"), Rec("b"));

        var (_, body) = await Get(await ClientFor(factory, "vetting_test_user"));

        Assert.Equal(new[] { "a", "b" }, Ids(body));
        Assert.Equal("Second", Data(body).GetProperty("items")[0].GetProperty("legalName").GetString());
    }

    [Fact]
    public async Task TheCacheIsKeptInTheDatabase_SoItSurvivesARestart()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(3));
        await Get(await ClientFor(factory, "vetting_test_user"));

        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        Assert.Equal(3, await db.FoodspaceBeneficiaryRecords.CountAsync());
    }

    // ── payload size on a poor connection ──────────────────────────────────────────

    [Fact]
    public async Task APageOfFifty_IsSmall_AndMuchSmallerOverTheWireWhenCompressed()
    {
        await using var factory = new VettingFactory();
        factory.Foodspace.Serve(Many(200));
        var client = await ClientFor(factory, "vetting_test_user", gzip: true);

        var response = await client.GetAsync(Path + "?page=1&pageSize=50");
        response.EnsureSuccessStatusCode();
        var wire = await response.Content.ReadAsByteArrayAsync();

        Assert.Contains("gzip", response.Content.Headers.ContentEncoding);
        using var unzipped = new MemoryStream();
        await using (var gz = new GZipStream(new MemoryStream(wire), CompressionMode.Decompress)) await gz.CopyToAsync(unzipped);
        var raw = (int)unzipped.Length;

        _output.WriteLine($"MEASURED page of 50 records: {raw:N0} bytes raw JSON ({raw / 50.0:N0} B/record), {wire.Length:N0} bytes gzip ({100.0 * wire.Length / raw:N0}% of raw)");
        Assert.InRange(raw, 1, 150_000);          // a page stays well under 150 KB uncompressed
        Assert.True(wire.Length < raw / 3, "Compression should shrink repetitive JSON to under a third.");
    }

    // ── the Foodspace client itself ────────────────────────────────────────────────

    private sealed class StubHandler(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
    {
        public List<HttpRequestMessage> Requests { get; } = new();

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Requests.Add(request);
            return Task.FromResult(respond(request));
        }
    }

    private static FoodspaceApiClient NewClient(StubHandler handler)
    {
        var options = new FoodspaceOptions { BaseUrl = "http://foodspace.test" };
        var http = new HttpClient(handler) { BaseAddress = new Uri(options.BaseUrl) };
        return new FoodspaceApiClient(http, Microsoft.Extensions.Options.Options.Create(options), NullLogger<FoodspaceApiClient>.Instance);
    }

    private static HttpResponseMessage Json(string json, HttpStatusCode status = HttpStatusCode.OK) =>
        new(status) { Content = new StringContent(json, System.Text.Encoding.UTF8, "application/json") };

    private static string RecordJson(string id, string extra = "") =>
        JsonSerializer.Serialize(Rec(id), new JsonSerializerOptions(JsonSerializerDefaults.Web)).TrimEnd('}') + extra + "}";

    [Fact]
    public async Task Client_GetsTheBeneficiaryList_FromTheBeneficiariesEndpoint()
    {
        var handler = new StubHandler(_ => Json($"[{RecordJson("a")},{RecordJson("b")}]"));

        var result = await NewClient(handler).GetBeneficiariesAsync();

        Assert.Equal(FoodspaceOutcome.Success, result.Outcome);
        Assert.Equal(new[] { "a", "b" }, result.Records.Select(r => r.Id));
        Assert.Equal(HttpMethod.Get, handler.Requests.Single().Method);
        Assert.EndsWith("api/external/beneficiaries", handler.Requests.Single().RequestUri!.AbsolutePath.TrimStart('/'));
    }

    [Fact]
    public async Task Client_DropsAnythingFoodspaceSendsBeyondTheFieldsWeNeed()
    {
        var json = $"[{RecordJson("a", @",""internalScore"":99,""applicantIdNumber"":""8001015009087"",""kitchenImagesBase64"":""AAAA""")}]";

        var result = await NewClient(new StubHandler(_ => Json(json))).GetBeneficiariesAsync();

        var serialized = JsonSerializer.Serialize(result.Records.Single(), new JsonSerializerOptions(JsonSerializerDefaults.Web));
        Assert.DoesNotContain("internalScore", serialized);
        Assert.DoesNotContain("8001015009087", serialized);
        Assert.DoesNotContain("kitchenImagesBase64", serialized);
    }

    [Fact]
    public async Task Client_OneUnreadableRecord_IsSkippedAndCounted_TheRestAreKept()
    {
        var missingRequiredField = @"{""id"":""broken"",""legalName"":""No other fields""}";
        var wrongType = RecordJson("typo").Replace(@"""volunteers"":12", @"""volunteers"":""many""");
        var json = $"[{RecordJson("a")},{missingRequiredField},{wrongType},{RecordJson("b")},null,{{}}]";

        var result = await NewClient(new StubHandler(_ => Json(json))).GetBeneficiariesAsync();

        Assert.Equal(FoodspaceOutcome.Success, result.Outcome);
        Assert.Equal(new[] { "a", "b" }, result.Records.Select(r => r.Id));
        Assert.Equal(4, result.Skipped);
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
        var result = await NewClient(new StubHandler(_ => Json("{}", (HttpStatusCode)status))).GetBeneficiariesAsync();

        Assert.Equal(FoodspaceOutcome.Transient, result.Outcome);
        Assert.Empty(result.Records);
        Assert.Contains(status.ToString(), result.Error);
    }

    [Theory]
    [InlineData(400)]
    [InlineData(404)]
    public async Task Client_OtherClientErrors_ArePermanent(int status)
    {
        var result = await NewClient(new StubHandler(_ => Json("{}", (HttpStatusCode)status))).GetBeneficiariesAsync();

        Assert.Equal(FoodspaceOutcome.Permanent, result.Outcome);
    }

    [Theory]
    [InlineData(@"{""records"":[]}")]   // not an array
    [InlineData("not json at all")]
    [InlineData("")]
    public async Task Client_AnUnexpectedBody_IsPermanent_AndNeverThrows(string body)
    {
        var result = await NewClient(new StubHandler(_ => Json(body))).GetBeneficiariesAsync();

        Assert.Equal(FoodspaceOutcome.Permanent, result.Outcome);
        Assert.Empty(result.Records);
    }

    [Fact]
    public async Task Client_WhenFoodspaceIsUnreachable_IsTransient_InsteadOfThrowing()
    {
        var handler = new StubHandler(_ => throw new HttpRequestException("connection refused: secret-host.internal"));

        var result = await NewClient(handler).GetBeneficiariesAsync();

        Assert.Equal(FoodspaceOutcome.Transient, result.Outcome);
        Assert.DoesNotContain("secret-host", result.Error); // the stored/shown error never carries exception detail
    }

    [Fact]
    public async Task Client_AnErrorResponseBody_IsNeverKept()
    {
        var handler = new StubHandler(_ => Json(@"{""detail"":""internal stack trace and applicant data""}", HttpStatusCode.InternalServerError));

        var result = await NewClient(handler).GetBeneficiariesAsync();

        Assert.DoesNotContain("applicant data", result.Error);
    }
}
