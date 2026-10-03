using api.Data;
using api.DTOs;
using api.Models;
using api.Services.Foodspace;
using Microsoft.EntityFrameworkCore;

namespace api.Services;

public enum ResolutionStatus
{
    Ok,

    /// <summary>No record has that id.</summary>
    NotFound,

    /// <summary>Both forms have a record with that id (ids are chosen by devices); the caller must say which one.</summary>
    Ambiguous,

    /// <summary>The record is not in a state that allows the request.</summary>
    NotAllowed,

    /// <summary>The request itself is unusable (e.g. a dismissal with no reason).</summary>
    Invalid
}

/// <summary>The outcome of an Admin request: <see cref="Value"/> is set when <see cref="Status"/> is Ok, <see cref="Message"/> when it is not.</summary>
public record ResolutionResult<T>(ResolutionStatus Status, T? Value = default, string? Message = null)
{
    public static ResolutionResult<T> Ok(T value) => new(ResolutionStatus.Ok, value);
    public static ResolutionResult<T> Fail(ResolutionStatus status, string message) => new(status, default, message);
}

public interface IAdminSyncResolutionService
{
    /// <summary>The records that need an Admin, oldest first. <paramref name="form"/> narrows to one kind of record.</summary>
    Task<SyncAttentionResponse> ListAttentionAsync(SyncForm? form, int page, int pageSize, CancellationToken cancellationToken = default);

    Task<ResolutionResult<SyncRecordDetail>> GetAsync(string id, SyncForm? form, CancellationToken cancellationToken = default);

    /// <summary>Sends the record to Foodspace now and returns where it ended up. See <see cref="ICboCollectionForwarder.RetryAsync"/>.</summary>
    Task<ResolutionResult<ResolutionResponse>> RetryAsync(string id, SyncForm? form, string admin, CancellationToken cancellationToken = default);

    /// <summary>Marks a record that needs an Admin as never to be sent, with the reason kept in the audit trail.</summary>
    Task<ResolutionResult<ResolutionResponse>> DismissAsync(string id, SyncForm? form, string? reason, string admin, CancellationToken cancellationToken = default);
}

/// <summary>
/// What an Admin can do about a record that would not sync (#50): read why, retry it, or dismiss it. Every retry and
/// dismissal is written to <c>admin_actions</c> and to the log (who, which record, when, and what came of it).
///
/// What is NOT here, on purpose: editing a record's fields before resubmitting. A record is what the collector or officer
/// captured in the field, Foodspace only tells us a status code (never which field it disliked), and there is no real
/// failure data yet to say which field is worth making editable. See docs/decisions/0002-failed-sync-resolution.md.
/// </summary>
public class AdminSyncResolutionService : IAdminSyncResolutionService
{
    public const int MaxReasonLength = 500;
    private const int HistoryLength = 20;

    private readonly AppDbContext _db;
    private readonly ICboCollectionForwarder _collections;
    private readonly IVettingDecisionForwarder _decisions;
    private readonly TimeProvider _time;
    private readonly ILogger<AdminSyncResolutionService> _logger;

    public AdminSyncResolutionService(
        AppDbContext db,
        ICboCollectionForwarder collections,
        IVettingDecisionForwarder decisions,
        TimeProvider time,
        ILogger<AdminSyncResolutionService> logger)
    {
        _db = db;
        _collections = collections;
        _decisions = decisions;
        _time = time;
        _logger = logger;
    }

    // ── list ───────────────────────────────────────────────────────────────────────

    public async Task<SyncAttentionResponse> ListAttentionAsync(SyncForm? form, int page, int pageSize, CancellationToken cancellationToken = default)
    {
        var take = page * pageSize; // each table can contribute at most this many of the first `take` items
        var items = new List<SyncAttentionItem>();
        var total = 0;

        if (form is null or SyncForm.CboCollection)
        {
            var query = _db.CboCollections.Where(c =>
                (c.ForwardingStatus == ForwardingStatus.SyncedLocalPendingFoodspace && c.NextForwardAttemptAt == null)
                || (c.ForwardingStatus == ForwardingStatus.Pending && c.DuplicateOfId != null));
            total += await query.CountAsync(cancellationToken);
            var rows = await query.OrderBy(c => c.ReceivedAt).ThenBy(c => c.Id).Take(take).ToListAsync(cancellationToken);
            items.AddRange(rows.Select(c => ToItem(c, SyncForm.CboCollection)));
        }

        if (form is null or SyncForm.VettingDecision)
        {
            var query = _db.VettingDecisions.Where(d =>
                d.ForwardingStatus == ForwardingStatus.SyncedLocalPendingFoodspace && d.NextForwardAttemptAt == null);
            total += await query.CountAsync(cancellationToken);
            var rows = await query.OrderBy(d => d.ReceivedAt).ThenBy(d => d.Id).Take(take).ToListAsync(cancellationToken);
            items.AddRange(rows.Select(d => ToItem(d, SyncForm.VettingDecision)));
        }

        var pageItems = items.OrderBy(i => i.ReceivedAt).ThenBy(i => i.Id).Skip((page - 1) * pageSize).Take(pageSize).ToList();
        return new SyncAttentionResponse(pageItems, page, pageSize, total, HasMore: page * pageSize < total);
    }

    // ── one record ─────────────────────────────────────────────────────────────────

    public async Task<ResolutionResult<SyncRecordDetail>> GetAsync(string id, SyncForm? form, CancellationToken cancellationToken = default)
    {
        var found = await FindAsync(id, form, cancellationToken);
        if (found.Failure is { } failure) return ResolutionResult<SyncRecordDetail>.Fail(failure.Status, failure.Message);

        return ResolutionResult<SyncRecordDetail>.Ok(await ToDetailAsync(found.Record!, found.Form, cancellationToken));
    }

    // ── actions ────────────────────────────────────────────────────────────────────

    public async Task<ResolutionResult<ResolutionResponse>> RetryAsync(
        string id, SyncForm? form, string admin, CancellationToken cancellationToken = default)
    {
        var found = await FindAsync(id, form, cancellationToken);
        if (found.Failure is { } failure) return ResolutionResult<ResolutionResponse>.Fail(failure.Status, failure.Message);

        var record = found.Record!;
        var previous = StateOf(record);
        if (previous is SyncState.Forwarded)
            return ResolutionResult<ResolutionResponse>.Fail(ResolutionStatus.NotAllowed, "Foodspace already has this record.");
        if (previous is SyncState.Superseded)
            return ResolutionResult<ResolutionResponse>.Fail(ResolutionStatus.NotAllowed, "A newer decision replaced this one, so it is never sent.");

        var previousStatus = record.ForwardingStatus;
        var previousAttempts = record.SyncAttempts;
        _logger.LogInformation("Admin {Admin} is retrying {Form} {Id} (was {State}, {Attempts} attempt(s)).",
            admin, EnumWire.Of(found.Form), id, EnumWire.Of(previous), previousAttempts);

        // Both forwarders load the record through the same scoped DbContext, so `record` is the very instance they update.
        if (found.Form == SyncForm.CboCollection) await _collections.RetryAsync(id, cancellationToken);
        else await _decisions.RetryAsync(id, cancellationToken);

        await RecordActionAsync(admin, AdminActionType.Retry, found.Form, id, previousStatus, record.ForwardingStatus, previousAttempts, reason: null, cancellationToken);

        var detail = await ToDetailAsync(record, found.Form, cancellationToken);
        _logger.LogInformation("Admin {Admin} retried {Form} {Id}: it is now {State}.", admin, EnumWire.Of(found.Form), id, detail.State);
        return ResolutionResult<ResolutionResponse>.Ok(new ResolutionResponse(EnumWire.Of(previous), detail));
    }

    public async Task<ResolutionResult<ResolutionResponse>> DismissAsync(
        string id, SyncForm? form, string? reason, string admin, CancellationToken cancellationToken = default)
    {
        reason = reason?.Trim();
        if (string.IsNullOrEmpty(reason))
            return ResolutionResult<ResolutionResponse>.Fail(ResolutionStatus.Invalid, "A reason is required: a dismissed record is never sent to Foodspace.");
        if (reason.Length > MaxReasonLength)
            return ResolutionResult<ResolutionResponse>.Fail(ResolutionStatus.Invalid, $"The reason can be at most {MaxReasonLength} characters.");

        var found = await FindAsync(id, form, cancellationToken);
        if (found.Failure is { } failure) return ResolutionResult<ResolutionResponse>.Fail(failure.Status, failure.Message);

        var record = found.Record!;
        var previous = StateOf(record);
        if (!previous.NeedsAdmin())
            return ResolutionResult<ResolutionResponse>.Fail(ResolutionStatus.NotAllowed,
                "Only a record that needs attention, or a held suspected duplicate, can be dismissed.");

        var previousStatus = record.ForwardingStatus;
        record.ForwardingStatus = ForwardingStatus.Dismissed;
        record.NextForwardAttemptAt = null;
        // SyncError is kept: it is why the record needed an Admin, and part of what the audit trail explains.

        try
        {
            await RecordActionAsync(admin, AdminActionType.Dismiss, found.Form, id, previousStatus, record.ForwardingStatus, record.SyncAttempts, reason, cancellationToken);
        }
        catch (DbUpdateConcurrencyException)
        {
            // A forwarding worker claimed the record between this request reading it and writing it (see ForwardingClaim).
            // Nothing was saved, neither the dismissal nor its audit entry, so the Admin can simply ask again.
            _logger.LogInformation("Admin {Admin} could not dismiss {Form} {Id}: it was being sent to Foodspace at that moment.", admin, EnumWire.Of(found.Form), id);
            return ResolutionResult<ResolutionResponse>.Fail(ResolutionStatus.NotAllowed,
                "This record is being sent to Foodspace right now. Try again in a moment.");
        }
        _logger.LogInformation("Admin {Admin} dismissed {Form} {Id} (was {State}).", admin, EnumWire.Of(found.Form), id, EnumWire.Of(previous));

        return ResolutionResult<ResolutionResponse>.Ok(new ResolutionResponse(EnumWire.Of(previous), await ToDetailAsync(record, found.Form, cancellationToken)));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private sealed record Found(ForwardedEntity? Record, SyncForm Form, (ResolutionStatus Status, string Message)? Failure);

    /// <summary>Finds the record. Ids are chosen by devices, so the same id could in theory exist in both tables: then the caller must say which.</summary>
    private async Task<Found> FindAsync(string id, SyncForm? form, CancellationToken cancellationToken)
    {
        ForwardedEntity? collection = null, decision = null;
        if (form is null or SyncForm.CboCollection)
            collection = await _db.CboCollections.SingleOrDefaultAsync(c => c.Id == id, cancellationToken);
        if (form is null or SyncForm.VettingDecision)
            decision = await _db.VettingDecisions.SingleOrDefaultAsync(d => d.Id == id, cancellationToken);

        if (collection is not null && decision is not null)
            return new Found(null, default, (ResolutionStatus.Ambiguous, "Both a collection and a decision have that id. Add form=CBO_COLLECTION or form=VETTING_DECISION."));
        if (collection is not null) return new Found(collection, SyncForm.CboCollection, null);
        if (decision is not null) return new Found(decision, SyncForm.VettingDecision, null);
        return new Found(null, default, (ResolutionStatus.NotFound, "No such record."));
    }

    private static SyncState StateOf(ForwardedEntity record) =>
        SyncStates.Of(record.ForwardingStatus, record.NextForwardAttemptAt != null, record is CboCollection { DuplicateOfId: not null });

    private static string LabelOf(ForwardedEntity record) => SyncRecordInfo.LabelOf(record);

    private static string? SubmitterOf(ForwardedEntity record) => SyncRecordInfo.SubmitterOf(record);

    private static SyncAttentionItem ToItem(ForwardedEntity record, SyncForm form) => new(
        record.Id, EnumWire.Of(form), EnumWire.Of(StateOf(record)), LabelOf(record), record.ReceivedAt, SubmitterOf(record),
        record.SyncAttempts, record.LastSyncAttemptAt, record.SyncError);

    private async Task<SyncRecordDetail> ToDetailAsync(ForwardedEntity record, SyncForm form, CancellationToken cancellationToken)
    {
        var state = StateOf(record);

        DuplicateOfSummary? original = null;
        if (record is CboCollection { DuplicateOfId: { } originalId })
        {
            var o = await _db.CboCollections.AsNoTracking().SingleOrDefaultAsync(c => c.Id == originalId, cancellationToken);
            if (o is not null) original = new DuplicateOfSummary(o.Id, LabelOf(o), EnumWire.Of(StateOf(o)), o.ReceivedAt);
        }

        var history = await _db.AdminActions.AsNoTracking()
            .Where(a => a.Form == form && a.RecordId == record.Id)
            .OrderByDescending(a => a.OccurredAt)
            .Take(HistoryLength)
            .ToListAsync(cancellationToken);

        return new SyncRecordDetail(
            record.Id, EnumWire.Of(form), EnumWire.Of(state), LabelOf(record), record.ReceivedAt, SubmitterOf(record),
            record.SyncAttempts, record.LastSyncAttemptAt, record.NextForwardAttemptAt, record.SyncError, original,
            CanRetry: state is not (SyncState.Forwarded or SyncState.Superseded),
            CanDismiss: state.NeedsAdmin(),
            History: history.Select(a => new AdminActionEntry(
                a.OccurredAt, a.Admin, EnumWire.Of(a.Action), a.Reason, EnumWire.Of(a.ResultStatus))).ToList());
    }

    private async Task RecordActionAsync(
        string admin, AdminActionType action, SyncForm form, string recordId,
        ForwardingStatus previous, ForwardingStatus result, int previousAttempts, string? reason, CancellationToken cancellationToken)
    {
        _db.AdminActions.Add(new AdminAction
        {
            Id = Guid.NewGuid(),
            OccurredAt = _time.GetUtcNow(),
            Admin = admin,
            Action = action,
            Form = form,
            RecordId = recordId,
            PreviousStatus = previous,
            ResultStatus = result,
            PreviousAttempts = previousAttempts,
            Reason = reason
        });
        await _db.SaveChangesAsync(cancellationToken);
    }
}
