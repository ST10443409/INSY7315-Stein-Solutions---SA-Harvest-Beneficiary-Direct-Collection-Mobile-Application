# 0001: Admin lives inside the Android app

**Status:** accepted for #48 (assumed by the implementer on the recommendation in the issue; revisit if the team prefers a web dashboard)
**Context:** the project overview says Admin users "manage both workflows, monitor synchronisation status, and oversee user
activity". Issue #48 asks for an explicit choice between a third role-gated screen set in the Android app and a separate web
dashboard, because the choice changes the scope of #49 (sync monitoring), #50 (failed-sync resolution) and #51 (user activity).

## Decision
Admin is a role-gated section of the Android app: the `ADMIN` role gets its own navigation graph, and the dashboard links to
the two workflows (Form 1, Form 2) and to three oversight sections.

## Why
* **Reuse.** The Admin graph, role gating, login, JWT, Hilt and the Compose theme and components already exist. A web
  dashboard would be a second frontend with its own framework, hosting, authentication and CI, and #49 to #51 do not budget for that.
* **Risk and time.** Sprint 3 is nearly over; the in-app option is the lower-risk one (as #48 itself notes).
* **Same data, same API.** Either choice needs the same Admin-only backend endpoints; the choice only decides where they are shown.

## Consequences
* #49, #50 and #51 are in-app screens, each fed by an Admin-only endpoint (`[Authorize(Roles = ADMIN)]`).
* The Admin experience is phone-sized: long tables, bulk actions and filtering are harder than on a desktop.
* The backend does not depend on this choice. If a web dashboard is wanted later, it can call the same Admin endpoints with the
  same JWT, and the in-app screens can stay.

## How the shell is built
Role gating is by graph: only the `ADMIN` graph registers the Admin routes, so another role cannot navigate to them
(`RoleNavigationTest`). Each section is a route in that graph; until its issue lands it is a labelled "coming soon" screen.

| Section | Route | Built in |
|---|---|---|
| Form 1 (as an Admin) | `admin_form1` | #33 (done) |
| Form 2 (as an Admin) | `admin_form2` | #44 (done) |
| Sync monitoring | `admin_sync_monitor` | #49 (done) |
| Failed-sync resolution | `admin_failed_sync` | #50 |
| User activity | `admin_user_activity` | #51 |
