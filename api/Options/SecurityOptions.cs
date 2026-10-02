using System.ComponentModel.DataAnnotations;

namespace api.Options;

/// <summary>
/// Transport and abuse protections, bound from the "Security" configuration section (#54). The defaults are the
/// production ones; appsettings.Development.json relaxes <see cref="RequireHttps"/> for plain-HTTP local development.
/// </summary>
public class SecurityOptions
{
    public const string SectionName = "Security";

    /// <summary>
    /// Refuse API calls that did not arrive over HTTPS (403 <c>HTTPS_REQUIRED</c>; <c>/api/health</c> excepted for platform
    /// probes), send HSTS, and refuse to start unless Foodspace and the database are reached over TLS too. Behind a
    /// TLS-terminating proxy the request counts as HTTPS when the proxy says so in <c>X-Forwarded-Proto</c> and is listed
    /// in <see cref="KnownNetworks"/>. A misconfigured proxy therefore fails loudly (every call refused), never silently.
    /// </summary>
    public bool RequireHttps { get; set; } = true;

    [Range(1, 3650)]
    public int HstsMaxAgeDays { get; set; } = 365;

    /// <summary>
    /// CIDR ranges of the reverse proxy / load balancer whose <c>X-Forwarded-For</c> and <c>X-Forwarded-Proto</c> are
    /// believed (loopback always is). Anyone else's are ignored, so a client cannot claim HTTPS or another IP address.
    /// On a platform where the container is only reachable through its ingress (Azure Container Apps, App Service) that is
    /// the ingress' subnet, or 0.0.0.0/0 and ::/0 when the platform gives no fixed range.
    /// </summary>
    public List<string> KnownNetworks { get; set; } = new();
}
