using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using api.Data;
using api.Models;
using Microsoft.Extensions.DependencyInjection;

namespace api.Tests;

/// <summary>GET /api/admin/user-activity (#51): Admin only, read-only, filterable by user, role and date range, newest first, paged, and never the whole history by default.</summary>
public class AdminUserActivityEndpointTests : IDisposable
{
    private const string Path = "/api/admin/user-activity";

    // A fresh app and database per test: the tests assert exact lists.
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

    private async Task<(HttpStatusCode Status, JsonElement Body)> Get(string query = "", string user = "admin_test_user")
    {
        var response = await (await ClientFor(user)).GetAsync(Path + (query.Length == 0 ? "" : "?" + query));
        return (response.StatusCode, await response.Content.ReadFromJsonAsync<JsonElement>());
    }

    private async Task<JsonElement> Data(string query = "")
    {
        var (status, body) = await Get(query);
        Assert.Equal(HttpStatusCode.OK, status);
        Assert.True(body.GetProperty("success").GetBoolean());
        return body.GetProperty("data");
    }

    private static string Ids(JsonElement data) => string.Join(",", data.GetProperty("items").EnumerateArray().Select(i => i.GetProperty("id").GetString()));

    private void Seed(Action<AppDbContext> add)
    {
        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        add(db);
        db.SaveChanges();
    }

    /// <summary>Epoch milliseconds of a moment given in South African time (UTC+2).</summary>
    private static long Sa(int y, int m, int d, int h = 12, int min = 0) =>
        new DateTimeOffset(y, m, d, h, min, 0, TimeSpan.FromHours(2)).ToUnixTimeMilliseconds();

    private static long DaysAgo(int days) => DateTimeOffset.UtcNow.AddDays(-days).ToUnixTimeMilliseconds();

    private static CboCollection Collection(string id, string? by, long at) => new()
    {
        Id = id, CboId = ApiFactory.CboId, ArrivalTime = "10:00", DonorName = $"Donor {id}", DeliveryNote = $"DN-{id}", CollectNotes = "",
        SubmittedBy = by, CreatedAt = at, UpdatedAt = at, ReceivedAt = DateTimeOffset.FromUnixTimeMilliseconds(at).AddMinutes(5),
    };

    private static VettingDecision Decision(string id, string officer, long at, DecisionOutcome outcome = DecisionOutcome.Approve) => new()
    {
        Id = id, FoodspaceRecordId = "fs-1", OfficerId = officer, Outcome = outcome, DecisionTimestamp = at, CreatedAt = at, UpdatedAt = at,
        ReceivedAt = DateTimeOffset.FromUnixTimeMilliseconds(at).AddMinutes(5),
    };

    // ── access ─────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task WithoutAToken_Is401()
    {
        var response = await (await ClientFor(null)).GetAsync(Path);
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Theory]
    [InlineData("cbo_test_user")]
    [InlineData("vetting_test_user")]
    public async Task OtherRoles_AreForbidden(string username)
    {
        var (status, _) = await Get(user: username);
        Assert.Equal(HttpStatusCode.Forbidden, status);
    }

    // ── what it returns ────────────────────────────────────────────────────────────

    [Fact]
    public async Task EachItem_HasTheActingUser_TheirRole_TheTime_AndARecordReference()
    {
        var at = DaysAgo(1);
        Seed(db =>
        {
            db.CboCollections.Add(Collection("c1", "cbo_test_user", at));
            db.VettingDecisions.Add(Decision("d1", "vetting_test_user", at - 1000, DecisionOutcome.Reject));
        });

        var items = (await Data()).GetProperty("items").EnumerateArray().ToList();

        var c = items.Single(i => i.GetProperty("id").GetString() == "c1");
        Assert.Equal("CBO_COLLECTION", c.GetProperty("form").GetString());
        Assert.Equal("cbo_test_user", c.GetProperty("user").GetString());
        Assert.Equal("CBO_COLLECTION", c.GetProperty("role").GetString());
        Assert.Equal(DateTimeOffset.FromUnixTimeMilliseconds(at), c.GetProperty("at").GetDateTimeOffset());
        Assert.Equal(DateTimeOffset.FromUnixTimeMilliseconds(at).AddMinutes(5), c.GetProperty("receivedAt").GetDateTimeOffset());
        Assert.Contains("Donor c1", c.GetProperty("label").GetString());

        var d = items.Single(i => i.GetProperty("id").GetString() == "d1");
        Assert.Equal("VETTING_DECISION", d.GetProperty("form").GetString());
        Assert.Equal("vetting_test_user", d.GetProperty("user").GetString());
        Assert.Equal("VETTING", d.GetProperty("role").GetString());
        Assert.Contains("Reject", d.GetProperty("label").GetString());
    }

    [Fact]
    public async Task BothFormsShareOneList_NewestFirst()
    {
        Seed(db =>
        {
            db.CboCollections.AddRange(Collection("c-old", "cbo_test_user", DaysAgo(3)), Collection("c-new", "cbo_test_user", DaysAgo(1)));
            db.VettingDecisions.Add(Decision("d-mid", "vetting_test_user", DaysAgo(2)));
        });

        Assert.Equal("c-new,d-mid,c-old", Ids(await Data()));
    }

    [Fact]
    public async Task AnAdminsOwnWork_IsActivityToo_WithTheAdminRole()
    {
        Seed(db => db.VettingDecisions.Add(Decision("d1", "admin_test_user", DaysAgo(1))));

        var item = (await Data()).GetProperty("items")[0];

        Assert.Equal("ADMIN", item.GetProperty("role").GetString());
    }

    [Fact]
    public async Task ARecordWithNoRecordedSubmitter_IsStillListed_WithNoUser()
    {
        Seed(db => db.CboCollections.Add(Collection("old", null, DaysAgo(1))));

        var item = (await Data()).GetProperty("items")[0];

        Assert.Equal("old", item.GetProperty("id").GetString());
        Assert.Equal(JsonValueKind.Null, item.GetProperty("user").ValueKind);
        Assert.Equal(JsonValueKind.Null, item.GetProperty("role").ValueKind);
    }

    [Fact]
    public async Task AUserWhoNoLongerExists_IsListedWithNoRole()
    {
        Seed(db => db.VettingDecisions.Add(Decision("d1", "former_officer", DaysAgo(1))));

        var item = (await Data()).GetProperty("items")[0];

        Assert.Equal("former_officer", item.GetProperty("user").GetString());
        Assert.Equal(JsonValueKind.Null, item.GetProperty("role").ValueKind);
    }

    [Fact]
    public async Task NothingRecorded_IsAnEmptyList_NotAnError()
    {
        var data = await Data();

        Assert.Empty(data.GetProperty("items").EnumerateArray());
        Assert.Equal(0, data.GetProperty("totalCount").GetInt32());
        Assert.False(data.GetProperty("hasMore").GetBoolean());
    }

    // ── filters ────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task ByUser_OnlyThatUsersWork_InEitherForm()
    {
        Seed(db =>
        {
            db.CboCollections.AddRange(Collection("c-a", "cbo_test_user", DaysAgo(1)), Collection("c-b", "cbo_other_user", DaysAgo(1)));
            db.VettingDecisions.AddRange(Decision("d-a", "vetting_test_user", DaysAgo(1)), Decision("d-admin", "admin_test_user", DaysAgo(1)));
        });

        Assert.Equal("c-a", Ids(await Data("user=cbo_test_user")));
        Assert.Equal("d-a", Ids(await Data("user=vetting_test_user")));
    }

    [Fact]
    public async Task TheUserFilter_IgnoresCase()
    {
        Seed(db => db.CboCollections.Add(Collection("c1", "cbo_test_user", DaysAgo(1))));

        Assert.Equal("c1", Ids(await Data("user=CBO_Test_User")));
    }

    [Fact]
    public async Task ByRole_OnlyWorkByAccountsWithThatRole()
    {
        Seed(db =>
        {
            db.CboCollections.AddRange(Collection("c1", "cbo_test_user", DaysAgo(1)), Collection("c2", "cbo_other_user", DaysAgo(2)), Collection("c-admin", "admin_test_user", DaysAgo(3)));
            db.VettingDecisions.AddRange(Decision("d1", "vetting_test_user", DaysAgo(1)), Decision("d-admin", "admin_test_user", DaysAgo(2)));
        });

        Assert.Equal("d1", Ids(await Data("role=VETTING")));
        Assert.Equal("c1,c2", Ids(await Data("role=CBO_COLLECTION")));
        Assert.Equal("d-admin,c-admin", Ids(await Data("role=admin")));
    }

    [Fact]
    public async Task ByRole_LeavesOutRecordsWithNoSubmitter()
    {
        Seed(db => db.CboCollections.AddRange(Collection("old", null, DaysAgo(1)), Collection("c1", "cbo_test_user", DaysAgo(1))));

        Assert.Equal("c1", Ids(await Data("role=CBO_COLLECTION")));
    }

    [Fact]
    public async Task ByUserAndRole_BothHaveToMatch()
    {
        Seed(db => db.CboCollections.Add(Collection("c1", "cbo_test_user", DaysAgo(1))));

        Assert.Equal("c1", Ids(await Data("user=cbo_test_user&role=CBO_COLLECTION")));
        Assert.Equal("", Ids(await Data("user=cbo_test_user&role=VETTING")));
    }

    [Fact]
    public async Task ByDateRange_BothEndsAreInclusive_InSouthAfricanDays()
    {
        Seed(db =>
        {
            db.CboCollections.AddRange(
                Collection("before", "cbo_test_user", Sa(2026, 9, 9, 23, 59)),
                Collection("first-day", "cbo_test_user", Sa(2026, 9, 10, 0, 0)),
                Collection("middle", "cbo_test_user", Sa(2026, 9, 15)),
                Collection("last-day", "cbo_test_user", Sa(2026, 9, 20, 23, 59)),
                Collection("after", "cbo_test_user", Sa(2026, 9, 21, 0, 0)));
            db.VettingDecisions.Add(Decision("d-in", "vetting_test_user", Sa(2026, 9, 12)));
        });

        var data = await Data("from=2026-09-10&to=2026-09-20");

        Assert.Equal("last-day,middle,d-in,first-day", Ids(data));
        Assert.Equal("2026-09-10", data.GetProperty("from").GetString());
        Assert.Equal("2026-09-20", data.GetProperty("to").GetString());
    }

    [Fact]
    public async Task ADayIsASouthAfricanDay_NotAUtcDay()
    {
        // 22:30 UTC on the 20th is 00:30 on the 21st in South Africa.
        var at = new DateTimeOffset(2026, 9, 20, 22, 30, 0, TimeSpan.Zero).ToUnixTimeMilliseconds();
        Seed(db => db.CboCollections.Add(Collection("late", "cbo_test_user", at)));

        Assert.Equal("late", Ids(await Data("from=2026-09-21&to=2026-09-21")));
        Assert.Equal("", Ids(await Data("from=2026-09-20&to=2026-09-20")));
    }

    [Fact]
    public async Task OneEndOnly_IsAnOpenRange_AndGetsNoDefaultWindow()
    {
        Seed(db => db.CboCollections.AddRange(Collection("ancient", "cbo_test_user", Sa(2026, 1, 5)), Collection("recent", "cbo_test_user", DaysAgo(1))));

        Assert.Equal("recent,ancient", Ids(await Data("from=2026-01-01")));
        Assert.Equal("ancient", Ids(await Data("to=2026-01-31")));
    }

    [Fact]
    public async Task TheFiltersCombine_RoleWithinADateRange()
    {
        Seed(db =>
        {
            db.CboCollections.Add(Collection("c-in", "cbo_test_user", Sa(2026, 9, 15)));
            db.VettingDecisions.AddRange(Decision("d-in", "vetting_test_user", Sa(2026, 9, 15)), Decision("d-out", "vetting_test_user", Sa(2026, 8, 1)));
        });

        Assert.Equal("d-in", Ids(await Data("role=VETTING&from=2026-09-01&to=2026-09-20")));
    }

    // ── the default window ─────────────────────────────────────────────────────────

    [Fact]
    public async Task WithNoDates_OnlyTheLastSevenDaysAreReturned_AndTheWindowIsReported()
    {
        Seed(db => db.CboCollections.AddRange(
            Collection("today", "cbo_test_user", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - 1000),
            Collection("six-days-ago", "cbo_test_user", DaysAgo(6) + 3_600_000),
            Collection("a-month-ago", "cbo_test_user", DaysAgo(30))));

        var data = await Data();

        Assert.Equal("today,six-days-ago", Ids(data));
        var today = DateOnly.FromDateTime(DateTimeOffset.UtcNow.ToOffset(TimeSpan.FromHours(2)).DateTime);
        Assert.Equal(today.ToString("yyyy-MM-dd"), data.GetProperty("to").GetString());
        Assert.Equal(today.AddDays(-6).ToString("yyyy-MM-dd"), data.GetProperty("from").GetString());
    }

    [Fact]
    public async Task AFilterByUserAlone_StillGetsTheDefaultWindow()
    {
        Seed(db => db.CboCollections.AddRange(Collection("recent", "cbo_test_user", DaysAgo(1)), Collection("old", "cbo_test_user", DaysAgo(40))));

        Assert.Equal("recent", Ids(await Data("user=cbo_test_user")));
    }

    // ── paging ─────────────────────────────────────────────────────────────────────

    [Fact]
    public async Task TheList_IsPaged_AcrossBothForms_WithATotal()
    {
        Seed(db =>
        {
            db.CboCollections.AddRange(Collection("c1", "cbo_test_user", DaysAgo(1) + 1), Collection("c3", "cbo_test_user", DaysAgo(3)));
            db.VettingDecisions.AddRange(Decision("d2", "vetting_test_user", DaysAgo(2)), Decision("d4", "vetting_test_user", DaysAgo(4)));
        });

        var first = await Data("pageSize=3");
        var second = await Data("pageSize=3&page=2");

        Assert.Equal("c1,d2,c3", Ids(first));
        Assert.True(first.GetProperty("hasMore").GetBoolean());
        Assert.Equal(4, first.GetProperty("totalCount").GetInt32());
        Assert.Equal("d4", Ids(second));
        Assert.False(second.GetProperty("hasMore").GetBoolean());
    }

    // ── bad requests ───────────────────────────────────────────────────────────────

    [Theory]
    [InlineData("role=SUPERUSER")]
    [InlineData("role=7")]
    [InlineData("from=yesterday")]
    [InlineData("to=2026-13-40")]
    [InlineData("from=20-09-2026")]
    [InlineData("from=1999-12-31")]
    [InlineData("to=9999-12-31")]
    [InlineData("from=2026-09-20&to=2026-09-10")]
    [InlineData("page=0")]
    [InlineData("pageSize=0")]
    [InlineData("pageSize=101")]
    public async Task ABadQuery_Is400_WithTheFieldNamed(string query)
    {
        var (status, body) = await Get(query);

        Assert.Equal(HttpStatusCode.BadRequest, status);
        Assert.False(body.GetProperty("success").GetBoolean());
        Assert.Equal("VALIDATION_FAILED", body.GetProperty("error").GetProperty("code").GetString());
        Assert.NotEmpty(body.GetProperty("error").GetProperty("details").EnumerateObject());
    }

    // ── read-only ──────────────────────────────────────────────────────────────────

    [Fact]
    public async Task ItOnlyReads_NothingIsChanged()
    {
        Seed(db =>
        {
            db.CboCollections.Add(Collection("c1", "cbo_test_user", DaysAgo(1)));
            db.VettingDecisions.Add(Decision("d1", "vetting_test_user", DaysAgo(1)));
        });

        await Data();
        await Data("role=VETTING");

        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        Assert.Equal(0, db.AdminActions.Count());
        Assert.Equal(1, db.CboCollections.Count());
        Assert.Equal(1, db.VettingDecisions.Count());
    }

    [Fact]
    public async Task ThePostVerb_DoesNotExist()
    {
        var response = await (await ClientFor("admin_test_user")).PostAsJsonAsync(Path, new { });

        Assert.Equal(HttpStatusCode.MethodNotAllowed, response.StatusCode);
    }
}
