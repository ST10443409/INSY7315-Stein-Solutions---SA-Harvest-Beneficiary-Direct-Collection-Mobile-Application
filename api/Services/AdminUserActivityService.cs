using api.DTOs;
using api.Data;
using api.Models;
using Microsoft.EntityFrameworkCore;

namespace api.Services;

/// <summary>What to look for. A null filter means "any". <see cref="From"/> and <see cref="To"/> are South African calendar days, <see cref="To"/> inclusive.</summary>
public record UserActivityQuery(string? User, UserRole? Role, DateOnly? From, DateOnly? To, int Page, int PageSize);

public interface IAdminUserActivityService
{
    Task<UserActivityResponse> GetAsync(UserActivityQuery query, CancellationToken cancellationToken = default);
}

/// <summary>
/// Who submitted or vetted what, and when (#51): Form 1 collections and Form 2 decisions in one newest-first list,
/// filterable by user, role and date range. Read-only: nothing here changes a record (resolving failed syncs is #50).
///
/// "When" is the time on the device when the work was done (<c>createdAt</c> / <c>decisionTimestamp</c>), because that is what
/// "Tuesday's collections" means to a person; <c>receivedAt</c> says when the server heard about it. A user's role is the
/// one on their account today; the records only store the username.
/// </summary>
public class AdminUserActivityService : IAdminUserActivityService
{
    private readonly AppDbContext _db;

    public AdminUserActivityService(AppDbContext db) => _db = db;

    public async Task<UserActivityResponse> GetAsync(UserActivityQuery query, CancellationToken cancellationToken = default)
    {
        var fromMillis = query.From is { } from ? StartOfDay(from) : (long?)null;
        var toMillis = query.To is { } to ? StartOfDay(to.AddDays(1)) : (long?)null; // exclusive: the end of the inclusive day
        var user = string.IsNullOrWhiteSpace(query.User) ? null : query.User.Trim().ToLowerInvariant(); // usernames are stored lower-case
        var take = query.Page * query.PageSize; // each table can contribute at most this many of the first `take` items

        // Usernames holding the role. The records only store the username, so the role filter is "submitted by one of these".
        var roleNames = query.Role is { } role ? _db.Users.Where(u => u.Role == role).Select(u => u.Username) : null;

        var collections = _db.CboCollections.AsQueryable();
        if (user is not null) collections = collections.Where(c => c.SubmittedBy == user);
        if (roleNames is not null) collections = collections.Where(c => c.SubmittedBy != null && roleNames.Contains(c.SubmittedBy));
        if (fromMillis is { } f1) collections = collections.Where(c => c.CreatedAt >= f1);
        if (toMillis is { } t1) collections = collections.Where(c => c.CreatedAt < t1);

        var decisions = _db.VettingDecisions.AsQueryable();
        if (user is not null) decisions = decisions.Where(d => d.OfficerId == user);
        if (roleNames is not null) decisions = decisions.Where(d => roleNames.Contains(d.OfficerId));
        if (fromMillis is { } f2) decisions = decisions.Where(d => d.DecisionTimestamp >= f2);
        if (toMillis is { } t2) decisions = decisions.Where(d => d.DecisionTimestamp < t2);

        var total = await collections.CountAsync(cancellationToken) + await decisions.CountAsync(cancellationToken);

        var collectionRows = await collections.OrderByDescending(c => c.CreatedAt).ThenBy(c => c.Id).Take(take).ToListAsync(cancellationToken);
        var decisionRows = await decisions.OrderByDescending(d => d.DecisionTimestamp).ThenBy(d => d.Id).Take(take).ToListAsync(cancellationToken);

        var page = collectionRows.Select(c => (Entity: (ForwardedEntity)c, Form: SyncForm.CboCollection, At: c.CreatedAt))
            .Concat(decisionRows.Select(d => (Entity: (ForwardedEntity)d, Form: SyncForm.VettingDecision, At: d.DecisionTimestamp)))
            .OrderByDescending(x => x.At).ThenBy(x => x.Entity.Id)
            .Skip((query.Page - 1) * query.PageSize).Take(query.PageSize)
            .ToList();

        var names = page.Select(x => SyncRecordInfo.SubmitterOf(x.Entity)).OfType<string>().Distinct().ToList();
        var roles = await _db.Users.Where(u => names.Contains(u.Username)).ToDictionaryAsync(u => u.Username, u => u.Role, cancellationToken);

        var items = page.Select(x =>
        {
            var who = SyncRecordInfo.SubmitterOf(x.Entity);
            return new UserActivityItem(
                x.Entity.Id, EnumWire.Of(x.Form), who, who is not null && roles.TryGetValue(who, out var r) ? r.ToString() : null,
                DateTimeOffset.FromUnixTimeMilliseconds(x.At), x.Entity.ReceivedAt, SyncRecordInfo.LabelOf(x.Entity));
        }).ToList();

        return new UserActivityResponse(
            items, query.Page, query.PageSize, total, HasMore: query.Page * query.PageSize < total,
            From: query.From?.ToString("yyyy-MM-dd"), To: query.To?.ToString("yyyy-MM-dd"));
    }

    /// <summary>Epoch milliseconds of midnight at the start of this South African day.</summary>
    private static long StartOfDay(DateOnly day) =>
        new DateTimeOffset(day.ToDateTime(TimeOnly.MinValue), SyncRecordInfo.SouthAfrica).ToUnixTimeMilliseconds();
}
