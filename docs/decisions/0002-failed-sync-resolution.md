# 0002: How an Admin resolves a record that would not sync

**Status:** accepted for #50 (assumptions about duplicates are listed in `docs/OPEN-DECISIONS.md`, section 2)
**Context:** a record that reached the backend can still fail to reach Foodspace: Foodspace rejects it (a 4xx), or keeps
failing until the automatic retries run out. The record is safe on our side, but nothing moves it on, so without a way
to act it is effectively lost. #50 asks for: the error visible before acting, a manual retry, an audit trail of who did
what, and, where practical, a way to correct malformed data and resubmit (with "document what is and isn't editable" as the
floor).

## Decision

An Admin can do two things to a record that needs attention, and one more to a suspected duplicate:

| Action | What it does | Allowed when |
|---|---|---|
| **Retry** | Sends the record to Foodspace now and returns where it ended up. Starts the attempt count again. | Any record except one Foodspace already has, or a decision a newer one replaced (409) |
| **Release** (retry on a held duplicate) | The Admin has decided it is a real collection: it is sent, then behaves like any other record | `DUPLICATE_HELD` |
| **Dismiss** | Marks the record as never to be sent. A reason is required. | `NEEDS_ATTENTION` or `DUPLICATE_HELD` only (409 otherwise) |

Every retry and dismissal is written to an append-only `admin_actions` table (who, which record, when, previous and
resulting status, previous attempt count, reason) and to the log. The record's detail shows that history.

## Why

* **Retry resets the attempt count.** Automatic retries stop at `Foodspace:MaxAttempts`. If a manual retry kept the old count and
  Foodspace was still down, the record would land straight back in "needs attention" with no further automatic retry. Starting
  from zero puts it back on the normal backoff schedule. The count before the retry is kept in the audit row, so nothing is lost.
* **Release is retry, not a separate action.** Both mean "send it now". The only difference is that a held duplicate is not
  picked up by the background loop, so the Admin's click is what lets it go. After it is sent it is an ordinary record
  (the loop retries it if Foodspace is down); the link to the record it resembled is kept for the audit trail.
* **Dismiss exists because there is otherwise no way to clear a record that can never be sent.** Examples: a vetting decision
  for a beneficiary Foodspace has since removed (a permanent 4xx; `docs/OPEN-DECISIONS.md`, section 4 names this case), or a
  confirmed duplicate. Without it they sit in the list forever and bury the ones that matter. Because a dismissal is the
  closest thing to deleting field data, it needs a reason, only applies to records that already need an Admin, and can be
  undone by a retry. Nothing is ever deleted.
* **A shared state definition.** `SyncStates.Of` decides which state a record is in, and both the monitor's counts (#49)
  and this list use it, so "needs attention: 3" on the monitor always matches three rows in the list.
* **`DISMISSED` is a new `ForwardingStatus` value,** stored as text, so there is no schema change for it; only `admin_actions`
  needed a migration.

## What is NOT editable, and why (the "edit and resubmit" criterion)

**No record field can be edited by an Admin in this version.** The criterion allows documenting this instead, and it is the
right call for now:

* **We do not know what to edit.** Foodspace tells us only a status code ("answered 422"), never which field it disliked. The
  backend already validates the fields it can (`CboCollectionSyncValidator`, `VettingDecisionSyncValidator`) before storing,
  so a record that fails at Foodspace is one our rules accepted and Foodspace's rules did not. There is no failure data yet to
  say which field is worth making editable, and the issue itself says to confirm that from real failures first.
* **The record is evidence.** It is what a collector or officer captured in the field, including signatures and weights. An
  Admin who silently rewrites it changes the record of what happened. Any edit has to be an audited correction, not an overwrite.
* **An edit has knock-on effects.** Donor name and delivery note feed the duplicate key (`CboCollectionDuplicateKey`), so
  editing them can create or dissolve a duplicate; a decision's outcome changes what Foodspace is told.

**What it would take, once real failures show a pattern:** an allow-list of fields (the likely candidates are free text such as
`collectNotes` and a decision's `notes`, which cannot affect identity), the same validators run on the edited value, a new
`EDIT` action in `admin_actions` with the old and new value, and the same retry afterwards. The audit trail and retry in this
version are the foundation for that, so it can be added without reworking them.

Meanwhile the Admin has the error, the attempt count and the full record label to decide, and **Dismiss** plus asking the
collector or officer to submit a corrected record covers the genuinely malformed case.

## Consequences

* A collector who re-submits a corrected collection after the original was dismissed will have it flagged as a duplicate of the
  dismissed one (the duplicate key does not look at status). It is then held, and the Admin releases it. That is slightly
  more work than ideal; the alternative (ignoring dismissed originals when matching) makes duplicates harder to reason about
  and can be added to `CboCollectionDuplicateKey` handling if it proves annoying.
* A manual retry calls Foodspace inside the request, so it takes as long as Foodspace does (bounded by
  `Foodspace:TimeoutSeconds`, 15 s by default). The app shows "Retrying…" while it waits.
* The audit row is written after the attempt. If the process died between the two, the retry happened but is not in the trail;
  the log line written before the attempt is the backstop. Accepted: a manual retry is idempotent on Foodspace's side (it
  upserts by our id), so the cost of that gap is a missing audit row, not a wrong record.
* Two Admins acting on the same record at once is last-write-wins; the second sees a 409 if the first got it into a state that
  no longer allows the action.
