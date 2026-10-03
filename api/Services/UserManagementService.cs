using api.Data;
using api.DTOs;
using api.Models;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace api.Services;

public enum UserResultStatus
{
    Ok,

    /// <summary>No account has that id.</summary>
    NotFound,

    /// <summary>The request breaks a rule (bad username, weak password, wrong CBO for the role...). <c>Details</c> says which field.</summary>
    Invalid,

    /// <summary>The request is fine but not allowed in the current state (username taken, would lock out the last Admin...).</summary>
    Conflict
}

public sealed record UserResult<T>(UserResultStatus Status, T? Value, string? Message, IDictionary<string, string[]>? Details)
{
    public static UserResult<T> Ok(T value) => new(UserResultStatus.Ok, value, null, null);
    public static UserResult<T> NotFound(string message) => new(UserResultStatus.NotFound, default, message, null);
    public static UserResult<T> Conflict(string message) => new(UserResultStatus.Conflict, default, message, null);

    public static UserResult<T> Invalid(IDictionary<string, string[]> details) =>
        new(UserResultStatus.Invalid, default, "The request is not valid.", details);
}

public record UserListQuery(UserRole? Role, bool? IsActive, string? Search, int Page, int PageSize);

/// <summary>The Admin making a change: always the account that signed in, never something the request claims.</summary>
public record AdminActor(Guid? Id, string Username);

/// <summary>
/// Creating and managing login accounts (Admin only). Every change is checked against <see cref="AccountRules"/>, protected by
/// guard rails that stop an Admin locking everyone out, recorded in the audit trail in the same save, and, where it must end
/// existing sessions, rotates the account's <see cref="AppUser.SecurityStamp"/> (see <c>TokenAccountCheck</c>).
/// </summary>
public interface IUserManagementService
{
    Task<UserListResponse> ListAsync(UserListQuery query, CancellationToken cancellationToken = default);
    Task<UserResult<UserDetailDto>> GetAsync(Guid id, CancellationToken cancellationToken = default);
    Task<UserResult<UserDto>> CreateAsync(AdminActor actor, CreateUserRequest request, CancellationToken cancellationToken = default);
    Task<UserResult<UserDto>> UpdateAsync(AdminActor actor, Guid id, UpdateUserRequest request, CancellationToken cancellationToken = default);
    Task<UserResult<UserDto>> ResetPasswordAsync(AdminActor actor, Guid id, string newPassword, CancellationToken cancellationToken = default);
}

public class UserManagementService : IUserManagementService
{
    /// <summary>How many history lines an account's detail shows.</summary>
    public const int HistoryLength = 20;

    private readonly AppDbContext _db;
    private readonly IPasswordHasher<AppUser> _hasher;
    private readonly TimeProvider _time;
    private readonly ILogger<UserManagementService> _logger;

    public UserManagementService(AppDbContext db, IPasswordHasher<AppUser> hasher, TimeProvider time, ILogger<UserManagementService> logger)
    {
        _db = db;
        _hasher = hasher;
        _time = time;
        _logger = logger;
    }

    // ── read ───────────────────────────────────────────────────────────────────────

    public async Task<UserListResponse> ListAsync(UserListQuery query, CancellationToken cancellationToken = default)
    {
        var users = _db.Users.AsNoTracking().AsQueryable();
        if (query.Role is { } role) users = users.Where(u => u.Role == role);
        if (query.IsActive is { } active) users = users.Where(u => u.IsActive == active);
        if (!string.IsNullOrWhiteSpace(query.Search))
        {
            var term = AccountRules.NormalizeUsername(query.Search);
            users = users.Where(u => u.Username.Contains(term));
        }

        var total = await users.CountAsync(cancellationToken);
        var items = await users.OrderBy(u => u.Username)
            .Skip((query.Page - 1) * query.PageSize).Take(query.PageSize)
            .ToListAsync(cancellationToken);

        return new UserListResponse(items.Select(ToDto).ToList(), query.Page, query.PageSize, total, HasMore: query.Page * query.PageSize < total);
    }

    public async Task<UserResult<UserDetailDto>> GetAsync(Guid id, CancellationToken cancellationToken = default)
    {
        var user = await _db.Users.AsNoTracking().SingleOrDefaultAsync(u => u.Id == id, cancellationToken);
        if (user is null) return UserResult<UserDetailDto>.NotFound("No such user.");

        var history = await _db.UserAudit.AsNoTracking()
            .Where(a => a.TargetUserId == id)
            .OrderByDescending(a => a.OccurredAt)
            .Take(HistoryLength)
            .ToListAsync(cancellationToken);

        return UserResult<UserDetailDto>.Ok(new UserDetailDto(
            ToDto(user),
            history.Select(a => new UserAuditEntryDto(a.OccurredAt, a.Actor, EnumWire.Of(a.Action), a.Detail)).ToList()));
    }

    // ── create ─────────────────────────────────────────────────────────────────────

    public async Task<UserResult<UserDto>> CreateAsync(AdminActor actor, CreateUserRequest request, CancellationToken cancellationToken = default)
    {
        var problems = new Dictionary<string, string[]>();
        var username = AccountRules.NormalizeUsername(request.Username);

        if (AccountRules.UsernameProblem(username) is { } usernameProblem) problems["username"] = new[] { usernameProblem };

        var role = ParseRole(request.Role, problems, "role");
        if (role is { } r && AccountRules.CboProblem(r, request.CboId) is { } cboProblem) problems["cboId"] = new[] { cboProblem };
        if (AccountRules.PasswordProblem(username, request.Password) is { } passwordProblem) problems["password"] = new[] { passwordProblem };

        if (problems.Count > 0) return UserResult<UserDto>.Invalid(problems);

        if (await _db.Users.AnyAsync(u => u.Username == username, cancellationToken))
            return UserResult<UserDto>.Conflict("That username is already taken.");

        var user = new AppUser
        {
            Id = Guid.NewGuid(),
            Username = username,
            PasswordHash = "pending",
            Role = role!.Value,
            CboId = role == UserRole.CBO_COLLECTION ? request.CboId!.Trim() : null,
            IsActive = true,
            CreatedAt = _time.GetUtcNow(),
        };
        user.PasswordHash = _hasher.HashPassword(user, request.Password);

        _db.Users.Add(user);
        Audit(actor, UserAuditAction.Created, user, user.CboId is null ? $"role {user.Role}" : $"role {user.Role}, CBO {user.CboId}");

        try
        {
            await _db.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateException)
        {
            // Two Admins creating the same username at the same moment: the unique index decides, the loser is told.
            _db.ChangeTracker.Clear();
            if (await _db.Users.AnyAsync(u => u.Username == username, cancellationToken))
                return UserResult<UserDto>.Conflict("That username is already taken.");
            throw;
        }

        _logger.LogInformation("Admin {Admin} created account {Username} with role {Role}.", actor.Username, user.Username, user.Role);
        return UserResult<UserDto>.Ok(ToDto(user));
    }

    // ── update ─────────────────────────────────────────────────────────────────────

    public async Task<UserResult<UserDto>> UpdateAsync(AdminActor actor, Guid id, UpdateUserRequest request, CancellationToken cancellationToken = default)
    {
        var user = await _db.Users.SingleOrDefaultAsync(u => u.Id == id, cancellationToken);
        if (user is null) return UserResult<UserDto>.NotFound("No such user.");

        var problems = new Dictionary<string, string[]>();
        var newRole = request.Role is null ? user.Role : ParseRole(request.Role, problems, "role") ?? user.Role;

        // The CBO follows the role unless the request says otherwise: moving a collector to another role clears it, and a
        // collector's own CBO is kept when only something else changes.
        string? newCbo = request.CboId is not null
            ? (string.IsNullOrWhiteSpace(request.CboId) ? null : request.CboId.Trim())
            : (newRole == UserRole.CBO_COLLECTION ? user.CboId : null);
        if (!problems.ContainsKey("role") && AccountRules.CboProblem(newRole, newCbo) is { } cboProblem) problems["cboId"] = new[] { cboProblem };

        if (problems.Count > 0) return UserResult<UserDto>.Invalid(problems);

        var newActive = request.IsActive ?? user.IsActive;
        var roleChanged = newRole != user.Role;
        var cboChanged = newCbo != user.CboId;
        var activeChanged = newActive != user.IsActive;
        if (!roleChanged && !cboChanged && !activeChanged) return UserResult<UserDto>.Ok(ToDto(user)); // nothing to do

        // Guard rails: an Admin cannot lock themselves out, and the system cannot be left with nobody who can manage accounts.
        if (actor.Id == user.Id && !newActive) return UserResult<UserDto>.Conflict("You cannot deactivate your own account.");
        if (actor.Id == user.Id && roleChanged) return UserResult<UserDto>.Conflict("You cannot change your own role.");
        if (user.Role == UserRole.ADMIN && user.IsActive && (newRole != UserRole.ADMIN || !newActive))
        {
            var otherActiveAdmins = await _db.Users.CountAsync(u => u.Id != user.Id && u.Role == UserRole.ADMIN && u.IsActive, cancellationToken);
            if (otherActiveAdmins == 0) return UserResult<UserDto>.Conflict("That would leave no active administrator. Create or keep another Admin first.");
        }

        var oldRole = user.Role;
        var oldCbo = user.CboId;
        user.Role = newRole;
        user.CboId = newCbo;
        user.IsActive = newActive;
        user.SecurityStamp = Guid.NewGuid(); // ends the account's existing sessions: the new rights apply from the next sign-in

        if (roleChanged) Audit(actor, UserAuditAction.RoleChanged, user, $"{oldRole} to {newRole}");
        if (cboChanged) Audit(actor, UserAuditAction.CboChanged, user, $"{oldCbo ?? "none"} to {newCbo ?? "none"}");
        if (activeChanged) Audit(actor, newActive ? UserAuditAction.Reactivated : UserAuditAction.Deactivated, user, null);

        await _db.SaveChangesAsync(cancellationToken);
        _logger.LogInformation("Admin {Admin} changed account {Username}: role {Role}, CBO {Cbo}, active {Active}.", actor.Username, user.Username, user.Role, user.CboId ?? "none", user.IsActive);
        return UserResult<UserDto>.Ok(ToDto(user));
    }

    public async Task<UserResult<UserDto>> ResetPasswordAsync(AdminActor actor, Guid id, string newPassword, CancellationToken cancellationToken = default)
    {
        var user = await _db.Users.SingleOrDefaultAsync(u => u.Id == id, cancellationToken);
        if (user is null) return UserResult<UserDto>.NotFound("No such user.");

        if (AccountRules.PasswordProblem(user.Username, newPassword) is { } problem)
            return UserResult<UserDto>.Invalid(new Dictionary<string, string[]> { ["newPassword"] = new[] { problem } });

        user.PasswordHash = _hasher.HashPassword(user, newPassword);
        user.SecurityStamp = Guid.NewGuid(); // anyone signed in with the old password (a stolen phone) is signed out
        Audit(actor, UserAuditAction.PasswordReset, user, null);

        await _db.SaveChangesAsync(cancellationToken);
        _logger.LogInformation("Admin {Admin} reset the password of account {Username}.", actor.Username, user.Username);
        return UserResult<UserDto>.Ok(ToDto(user));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static UserRole? ParseRole(string? raw, Dictionary<string, string[]> problems, string field)
    {
        if (!string.IsNullOrWhiteSpace(raw) && Enum.TryParse<UserRole>(raw.Trim(), ignoreCase: true, out var role) && Enum.IsDefined(role))
            return role;
        problems[field] = new[] { $"{field} must be one of: {string.Join(", ", Enum.GetNames<UserRole>())}." };
        return null;
    }

    private void Audit(AdminActor actor, UserAuditAction action, AppUser target, string? detail) =>
        _db.UserAudit.Add(new UserAuditEntry
        {
            Id = Guid.NewGuid(),
            OccurredAt = _time.GetUtcNow(),
            Actor = actor.Username,
            Action = action,
            TargetUserId = target.Id,
            TargetUsername = target.Username,
            Detail = detail,
        });

    private static UserDto ToDto(AppUser u) => new(u.Id.ToString(), u.Username, u.Role.ToString(), u.CboId, u.IsActive, u.CreatedAt);
}
