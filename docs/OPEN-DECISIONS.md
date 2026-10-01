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

**Caution:** changing the rule only affects records received afterwards. `duplicate_key` is stored per record, so an
existing database needs a one-off backfill migration if old records must follow the new rule. Decide before real data is collected.
