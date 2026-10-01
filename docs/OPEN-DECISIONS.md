# Open decisions

Two behaviours in the backend were built on an assumption because the answer has to come from outside the dev team.
Neither blocks Sprint 3, but both are cheap to change now and expensive once real data exists. Each entry says who can
answer, what to ask, and exactly what changes with each answer.

## 1. How do we authenticate to Foodspace?

**Assumed:** a static API key sent in an `X-Api-Key` header (`FoodspaceOptions.ApiKey` / `ApiKeyHeader`).

**Ask the Foodspace team:**
1. Which mechanism do they use: static API key, OAuth2 client-credentials, mutual TLS, something else?
2. If a key: which header name, and is there a separate sandbox key and base URL?
3. Do they rate-limit, and what status do they return when throttled (we already treat 429, 5xx, 408 and 401/403 as retryable)?
4. Do they de-duplicate on their side, and on which field? (We send the client UUID as the record id.)
5. What do they return for a record they permanently reject, and is the error body safe to log?

**What changes with each answer**
| Answer | Change |
|---|---|
| Static key, different header | Config only: `Foodspace:ApiKeyHeader`. |
| OAuth2 client-credentials | The `AddHttpClient<IFoodspaceApiClient, ...>` set-up in `api/Program.cs` gains a token handler; nothing else (forwarder, worker, retry logic) changes. |
| Mutual TLS | Client certificate on the same `HttpClient` set-up. |
| They de-duplicate on our id | Nothing, retries are already idempotent. If they use another field, adjust `FoodspaceCboCollectionMapper`. |

Until answered, `Foodspace:ApiKey` must be treated as a placeholder and is only used against `external-api-sim`.

## 2. What counts as a duplicate collection?

**Assumed:** same CBO + same donor name + same collection date (South African calendar day of `createdAt`) + same delivery
note number. Case and extra whitespace are ignored. The rule lives in `CboCollectionDuplicateKey.For`.

**Ask the people who run collections:**
1. Can one CBO collect from the same donor twice in a day, and if so is the delivery note number always different?
2. Is the delivery note number always filled in? A blank note currently matches another blank note, so two un-numbered
   visits to one donor on the same day are flagged (a deliberate false positive: flagged records wait for an Admin, nothing is dropped).
3. Who reviews flagged duplicates, and what do they need on screen to decide (the original, the donor, the times)? This
   shapes the Admin work in #49/#50.
4. Is "the same donor" reliably the same spelling, or do we need a donor id from Foodspace?

**What changes with each answer**
| Answer | Change |
|---|---|
| Add or remove a field (e.g. include arrival time, drop the delivery note) | One method, `CboCollectionDuplicateKey.For`, plus its tests in `CboCollectionSyncEndpointTests`. |
| Blank delivery notes should never match | One condition in `For`. |
| Donor identity needs an id | New field on the record and form, which is a Room + API schema change (migration on both sides). |

**Also assumed (#50): what an Admin does with a flagged duplicate.** It is held, never sent on its own. The Admin sees the
record it was matched against (donor, delivery note, whether Foodspace has it, when it arrived) and either **releases** it
("not a duplicate, send it": the *Retry* action, after which it is an ordinary record) or **dismisses** it with a reason
(a confirmed duplicate: kept as history, never sent). Question 3 above is the one that can change this: if reviewers need
more on screen than that (the times, the collector), it is one field on `DuplicateOfSummary` plus the detail screen; if a
second person must approve a release, that is a new state, not a change to these two actions.

**Caution:** changing the rule only affects records received afterwards. `duplicate_key` is stored per record, so an
existing database needs a one-off backfill migration if old records must follow the new rule. Decide before real data is collected.

## 3. What can Foodspace's beneficiary endpoint actually do? (#43)

**Assumed:** the simulator's behaviour: `GET /api/external/beneficiaries` returns the whole list as a JSON array and can only filter
by `province`. The backend therefore caches the full list (`Foodspace:BeneficiaryCacheMinutes`) and does the paging itself.

**Ask the Foodspace team:**
1. Can the list be paged, and filtered by status (e.g. only records awaiting vetting) or by "changed since"? If yes, the
   backend can ask for less and the cache can refresh by delta instead of by full replace.
2. Does a record carry a last-modified timestamp, and how are records that leave the list reported (removed, or a status change)?
   A delta mode (`since=`) is not safe to add without an answer, because a device would never learn a record was removed.
3. Are `kitchenImages`, `facilityPhotos`, `npoCertificate`, `pboCertificate` and `certificates` links/identifiers, or inline
   image data? The payload figures in `api/README.md` assume links. Inline images would make a page many megabytes.
4. How many beneficiary records are awaiting vetting at a time (tens, thousands)? This sets whether a 15 minute full refresh is fine.

**What changes with each answer**
| Answer | Change |
|---|---|
| Foodspace can page / filter by status | `IFoodspaceApiClient.GetBeneficiariesAsync` asks for less; the cache and endpoint contract stay the same. |
| Foodspace exposes a change timestamp and removals | Add an optional `since` to `GET /api/vetting/records` and have the refresh fetch only changes. |
| Images are inline | Stop caching/serving them in the list; fetch them on demand from the detail view. This changes the Room entity and the endpoint. |

## 4. What should Foodspace hear when an officer changes their mind? (#47)

**Assumed:** only the officer's latest decision on a beneficiary is sent. Earlier ones stay on our side as history, marked
`SUPERSEDED`, and are never forwarded. Decisions are sent oldest first, so Foodspace's last word is always the newest.

**Ask the Foodspace team:**
1. Do they want every decision (a history), or only the current one? Does their endpoint treat a second decision on the same
   beneficiary as a replacement, or as an additional record?
2. What does their `vetting-decisions` endpoint return for a beneficiary it no longer has (we assume `4xx`, which we treat as
   permanent: the decision is kept and waits for an Admin)?
3. Is the officer identified by username, or do they need an id (the app and backend only have the username today)?

**What changes with each answer**
| Answer | Change |
|---|---|
| Foodspace wants the full history | Remove the supersede check in `VettingDecisionForwarder.AttemptAsync`; keep sending oldest first. |
| Foodspace needs an officer id, not a username | Map it in `FoodspaceVettingDecisionMapper` (and store the id on the user). |
| Foodspace removes beneficiaries once vetted | A later decision on one will be rejected permanently; the Admin tooling (#49/#50) is where those are cleared. |
