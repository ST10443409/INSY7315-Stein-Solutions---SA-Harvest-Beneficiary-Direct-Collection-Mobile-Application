using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using api.Models;
using Microsoft.Extensions.DependencyInjection;

namespace api.Tests;

public class SyncControllerEndpointTests : IClassFixture<ApiFactory>
{
    private const string Path = "/api/sync";
    private readonly ApiFactory _factory;

    public SyncControllerEndpointTests(ApiFactory factory) => _factory = factory;

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

    [Fact]
    public async Task WithoutAToken_Is401()
    {
        var response = await (await ClientFor(null)).PostAsJsonAsync(Path, new SyncPayload { Id = "test", Data = "data" });
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Theory]
    [InlineData("cbo_test_user", HttpStatusCode.Accepted)]
    [InlineData("vetting_test_user", HttpStatusCode.Accepted)]
    [InlineData("admin_test_user", HttpStatusCode.Accepted)]
    public async Task AuthorizedRoles_AreAccepted(string username, HttpStatusCode expected)
    {
        var response = await (await ClientFor(username)).PostAsJsonAsync(Path, new SyncPayload { Id = "test", Data = "data" });
        Assert.Equal(expected, response.StatusCode);
    }
}
