using api.Models;
using api.Services;

namespace api.Tests;

/// <summary>The one definition of what a username, a password and a CBO assignment must look like (see <see cref="AccountRules"/>).</summary>
public class AccountRulesTests
{
    [Theory]
    [InlineData("abc")]
    [InlineData("jane.doe")]
    [InlineData("cbo-collector_01")]
    [InlineData("9lives")]
    public void ValidUsernames_AreAccepted(string username) => Assert.Null(AccountRules.UsernameProblem(username));

    [Theory]
    [InlineData("ab")]                      // too short
    [InlineData("")]
    [InlineData("-leading-hyphen")]
    [InlineData(".leading-dot")]
    [InlineData("has space")]
    [InlineData("UPPER")]                   // the rule is for the stored (lower-case) form
    [InlineData("o'brien")]
    [InlineData("semi;colon")]
    [InlineData("a@b.org")]
    public void InvalidUsernames_AreRefused(string username) => Assert.NotNull(AccountRules.UsernameProblem(username));

    [Fact]
    public void AUsername_OfMoreThan64Characters_IsRefused()
    {
        Assert.Null(AccountRules.UsernameProblem(new string('a', 64)));
        Assert.NotNull(AccountRules.UsernameProblem(new string('a', 65)));
    }

    [Fact]
    public void NormalizingAUsername_TrimsAndLowerCases_LikeSignIn() =>
        Assert.Equal(AuthService.Normalize("  Jane.Doe "), AccountRules.NormalizeUsername("  Jane.Doe "));

    [Theory]
    [InlineData("a-long-enough-passphrase-1")]
    [InlineData("correct horse battery staple")]   // spaces are fine: a passphrase works
    [InlineData("Tr0ub4dor&3-and-more")]
    public void StrongEnoughPasswords_AreAccepted(string password) => Assert.Null(AccountRules.PasswordProblem("jane", password));

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("short")]
    [InlineData("elevenchars")]               // 11: one under the minimum
    [InlineData("aaaaaaaaaaaaaaaa")]          // repetitive
    [InlineData("abababababababab")]          // only two different characters
    [InlineData("                ")]          // only spaces
    [InlineData("has-a-control\tchar-1234")]
    public void WeakOrMalformedPasswords_AreRefused(string? password) => Assert.NotNull(AccountRules.PasswordProblem("jane", password));

    [Fact]
    public void ThePasswordLimits_AreInclusive()
    {
        string Varied(int length) => string.Concat(Enumerable.Repeat("abcdefghij", length / 10 + 1))[..length]; // not repetitive
        Assert.Null(AccountRules.PasswordProblem("jane", "abcdefghijkl")); // exactly 12
        Assert.Null(AccountRules.PasswordProblem("jane", Varied(128)));    // exactly 128
        Assert.NotNull(AccountRules.PasswordProblem("jane", Varied(129)));
    }

    [Theory]
    [InlineData("jane.doe", "my-JANE.DOE-password")]  // any case
    [InlineData("kgosi", "xxKGOSIxxxxxxxxxxx")]
    public void APasswordThatContainsTheUsername_IsRefused(string username, string password) =>
        Assert.NotNull(AccountRules.PasswordProblem(username, password));

    [Fact]
    public void TheReasons_NeverQuoteThePassword()
    {
        const string password = "very-secret-passphrase!!";
        foreach (var attempt in new[] { password, password + new string('x', 200), "jane" + password })
        {
            var reason = AccountRules.PasswordProblem("jane", attempt);
            Assert.DoesNotContain("very-secret", reason ?? "");
        }
    }

    [Fact]
    public void ACollector_NeedsACbo_AndTheOtherRolesMustNotHaveOne()
    {
        Assert.NotNull(AccountRules.CboProblem(UserRole.CBO_COLLECTION, null));
        Assert.NotNull(AccountRules.CboProblem(UserRole.CBO_COLLECTION, "   "));
        Assert.Null(AccountRules.CboProblem(UserRole.CBO_COLLECTION, "cbo-1"));
        Assert.NotNull(AccountRules.CboProblem(UserRole.CBO_COLLECTION, new string('c', 101)));

        foreach (var role in new[] { UserRole.VETTING, UserRole.ADMIN })
        {
            Assert.Null(AccountRules.CboProblem(role, null));
            Assert.Null(AccountRules.CboProblem(role, ""));
            Assert.NotNull(AccountRules.CboProblem(role, "cbo-1"));
        }
    }
}
