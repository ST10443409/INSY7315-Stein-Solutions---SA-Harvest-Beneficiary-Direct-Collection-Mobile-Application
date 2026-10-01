namespace api.Services.Foodspace;

/// <summary>The retry delay shared by every forwarder: the base delay doubled for each failed attempt so far, capped.</summary>
public static class ForwardingBackoff
{
    public static TimeSpan For(FoodspaceOptions options, int attempts)
    {
        var seconds = options.BaseDelaySeconds * Math.Pow(2, Math.Max(0, attempts - 1));
        return TimeSpan.FromSeconds(Math.Min(seconds, options.MaxDelaySeconds));
    }
}
