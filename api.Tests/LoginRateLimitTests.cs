using System.Net;
using System.Net.Http.Json;
using System.Text.Json;

namespace api.Tests;

/// <summary>Sign-in attempts are limited per client address (#54), so a password cannot be guessed at speed.</summary>
public class LoginRateLimitTests
{
    private static Task<HttpResponseMessage> Login(HttpClient client, string password) =>
        client.PostAsJsonAsync("/api/auth/login", new { username = "cbo_test_user", password });

    [Fact]
    public async Task OverTheLimit_AttemptsGet429_WithRetryAfter_EvenWithTheRightPassword()
    {
        using var factory = new ApiFactory { LoginPermitLimit = 3 };
        var client = factory.CreateClient();

        for (var i = 0; i < 3; i++)
            Assert.Equal(HttpStatusCode.Unauthorized, (await Login(client, "wrong-guess-" + i)).StatusCode);

        var blocked = await Login(client, ApiFactory.Password);

        Assert.Equal(HttpStatusCode.TooManyRequests, blocked.StatusCode);
        Assert.True(blocked.Headers.RetryAfter?.Delta > TimeSpan.Zero);
        var body = await blocked.Content.ReadFromJsonAsync<JsonElement>();
        Assert.False(body.GetProperty("success").GetBoolean());
        Assert.Equal("TOO_MANY_REQUESTS", body.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task OtherEndpoints_AreNotLimited()
    {
        using var factory = new ApiFactory { LoginPermitLimit = 1 };
        var client = factory.CreateClient();

        for (var i = 0; i < 5; i++)
            Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/api/health")).StatusCode);
    }
}
