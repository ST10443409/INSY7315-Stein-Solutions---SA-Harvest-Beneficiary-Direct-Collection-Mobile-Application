# User accounts

Who may sign in to the app, and as what. Accounts are created and managed by an **Admin** through the API (`/api/admin/users`); the
Android app has no screen for it yet, so use `curl` as below, or any HTTP client. There is no self-registration, and the
test-user seeder only exists in local development.

## The first administrator

A fresh deployment has no accounts and only an Admin can create one, so the first Admin comes from configuration
(`Bootstrap__AdminUsername`, and `Bootstrap__AdminPassword` as a Key Vault secret). It is created once at start-up, only if no
active Admin exists, and never changes an existing account. Setting it up on Azure, and removing it afterwards, is
[infra/README.md](../infra/README.md) section 7. Locally you do not need it: `docker compose up` seeds `admin_test_user`.

## Signing in as an Admin from a terminal

```bash
API=https://<your-app>.azurewebsites.net           # or http://localhost:5000
read -rs -p "Admin password: " P; echo
TOKEN=$(curl -s -X POST $API/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"<admin username>\",\"password\":\"$P\"}" | sed -nE 's/.*"token":"([^"]+)".*/\1/p'); unset P
AUTH="Authorization: Bearer $TOKEN"
```

A token lasts 60 minutes. Every call below uses `$AUTH`. Responses are the usual `{ success, data, error }` envelope.

## What an account is

| Field | Rule |
|---|---|
| `username` | 3 to 64 characters: lower-case letters, digits, `.`, `_`, `-`; starts with a letter or digit. Stored lower-case; sign-in ignores case. Cannot be taken twice. |
| `role` | `CBO_COLLECTION` (Form 1 only), `VETTING` (Form 2 only) or `ADMIN` (both forms and oversight). |
| `cboId` | **Required for a collector** (the CBO they collect for: every record they submit is stamped with it, so it must be right). **Refused for the other roles.** |
| password | At least 12 and at most 128 characters; spaces are fine, so a passphrase works; at least four different characters; no control characters; cannot contain the username. Only a hash is stored; it is never returned or logged. |

The password is the account's first one, **chosen by the Admin and handed over out of band** (in person, or a message the person
reads and deletes). There is no self-service password change yet: when someone forgets theirs, reset it as below.

## Everyday tasks

```bash
# Create a collector (201). The role may be written in any case.
curl -s -X POST $API/api/admin/users -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"username":"thabo.collector","role":"CBO_COLLECTION","cboId":"<the CBO id>","password":"<12+ characters>"}'

# Create a vetting officer (no cboId)
curl -s -X POST $API/api/admin/users -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"username":"anele.vetting","role":"VETTING","password":"<12+ characters>"}'

# List accounts. Filters (all optional): role, active, search (part of a username), page, pageSize (max 100)
curl -s "$API/api/admin/users?role=CBO_COLLECTION&active=true&search=thabo" -H "$AUTH"

# One account, with its history (who created or changed it, and when), newest first
curl -s $API/api/admin/users/<id> -H "$AUTH"

# Change a collector's CBO, or move someone to another role (any of role, cboId, isActive; absent fields are left alone)
curl -s -X PATCH $API/api/admin/users/<id> -H "$AUTH" -H 'Content-Type: application/json' -d '{"cboId":"<new CBO id>"}'
curl -s -X PATCH $API/api/admin/users/<id> -H "$AUTH" -H 'Content-Type: application/json' -d '{"role":"VETTING"}'

# A leaver, or a lost or stolen phone: deactivate. They cannot sign in, and a session already open stops at once.
curl -s -X PATCH $API/api/admin/users/<id> -H "$AUTH" -H 'Content-Type: application/json' -d '{"isActive":false}'
# ... and bring them back
curl -s -X PATCH $API/api/admin/users/<id> -H "$AUTH" -H 'Content-Type: application/json' -d '{"isActive":true}'

# Forgotten password, or after a lost phone: set a new one. Signs the account out everywhere.
curl -s -X POST $API/api/admin/users/<id>/reset-password -H "$AUTH" -H 'Content-Type: application/json' -d '{"newPassword":"<12+ characters>"}'
```

To find an account's `<id>`, list with `search=`. Accounts are **deactivated, never deleted**: the history of who submitted and
vetted what stays attributable (design document B9.6: every record carries the authenticated user's identity).

## How sessions end (why a lost phone is not a 60-minute problem)

Every token carries a *stamp* belonging to the account, and every request is checked against the account's current stamp. The stamp
changes whenever the account is **deactivated or reactivated, its role or CBO changes, or its password is reset**. After any of
those, the old token is refused (401), the app returns to the sign-in screen, and the person signs in again (or cannot, if
deactivated). Records the person captured and has not yet synced stay on the phone and sync when someone signs in on it
(the app only ever sends a record as the person who captured it).

## Guard rails (409 Conflict)

- You cannot **deactivate** or **change the role of your own account**, so an Admin cannot lock themselves out. Ask another Admin.
- The **last active Admin** cannot be deactivated or demoted. Create or keep another Admin first.
- A username that is taken, a collector without a CBO, a non-collector with one, a weak password: refused with the reason, field by field
  (`error.details`), and nothing is changed.

## The audit trail

Every create, role change, CBO change, deactivation, reactivation and password reset is recorded with who did it and when, in the same
save as the change, and shown in an account's history (`GET /api/admin/users/<id>`). The first Admin shows as created by
`system:bootstrap`. The trail never contains a password, a hash or a token. There is no endpoint to edit or delete it.

## Not built yet

- A screen in the Android app (the endpoints are ready to be called from one).
- Self-service password change and a forced change on first sign-in. Until then, hand over a password you have just set and reset it if
  it is ever shared.
- Checking a collector's `cboId` against a list of real CBOs: the `cbos` table is filled by sync, not by this, so a typo in a CBO id is
  accepted. Read it back from the response before handing the account over.
