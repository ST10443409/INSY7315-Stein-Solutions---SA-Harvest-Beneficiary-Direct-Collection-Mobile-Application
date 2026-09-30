using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Routing;

namespace api.Infrastructure;

/// <summary>Turns the <c>[controller]</c> route token into kebab-case: <c>AccessDemo</c> -> <c>access-demo</c>.</summary>
public sealed partial class KebabCaseParameterTransformer : IOutboundParameterTransformer
{
    public string? TransformOutbound(object? value) =>
        value is string s ? WordBoundary().Replace(s, "$1-$2").ToLowerInvariant() : null;

    [GeneratedRegex("([a-z0-9])([A-Z])")]
    private static partial Regex WordBoundary();
}
