# Step F — Cross-Tenant Unique-Constraint Collisions Under RLS

Status: **All three fixes IMPLEMENTED and tested — push_device_tokens (composite tenant-scoped
key), status_pages.slug (clean 409 via caught DataIntegrityViolationException, global uniqueness
preserved), and the dead public status page (narrow, auditable RLS bypass for slug resolution
only — see Fix 3). Nothing committed.**

---

## Background

While closing out Step E, `PushNotificationService.registerToken`'s upsert was found to 500
when two different tenants register the identical `expoPushToken` (e.g. a device reset, or a
reinstall under a different account) — reported at the time, not fixed, since it was outside
that step's scope. This step fixes it, plus a second, identical-shape bug found during the
required audit of every other RLS-protected table.

## Recon

### 1. The exact constraint and upsert code path

Schema (`V51__create_push_device_tokens.sql`):
```sql
CREATE UNIQUE INDEX idx_push_device_tokens_token ON push_device_tokens (expo_push_token);
```
Global — not scoped by `tenant_id`.

RLS (`V60__enable_rls_on_remaining_tenant_tables.sql`):
```sql
CREATE POLICY tenant_isolation ON push_device_tokens
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
```

Upsert (`PushNotificationService.registerToken`, before this fix):
```java
PushDeviceToken token = tokenRepository.findByExpoPushToken(expoPushToken)
    .orElseGet(() -> PushDeviceToken.builder().expoPushToken(expoPushToken).build());
token.setUserId(userId);
token.setTenantId(tenantId);
token.setPlatform(platform);
PushDeviceToken saved = tokenRepository.save(token);
```

### 2. Why it was a 500

The policy has only a `USING` clause, no separate `WITH CHECK`, so Postgres applies that same
expression to every command including `SELECT`. Tenant B's `findByExpoPushToken` is filtered to
`tenant_id = B` and can't see tenant A's row for the same token — it returns empty even though
the token physically exists in the table. Code falls into `orElseGet`, builds a **new**
(no-`@Id`) entity → Hibernate does an `INSERT`, not an `UPDATE`. **Unique indexes are enforced
against the whole physical table regardless of what a session's RLS policy lets it see** — the
`INSERT` collides with tenant A's row, `DataIntegrityViolationException` is thrown, nothing
catches it, and it surfaces as an uncaught 500.

Confirmed: the constraint is global rather than per-tenant, **and** RLS hides the conflicting
row from the SELECT half of the upsert while the constraint still sees it at INSERT time — both
exactly as suspected.

### 3. Same pattern elsewhere — audited every RLS-protected table

Enumerated every table under `ENABLE ROW LEVEL SECURITY` (34 tables, across `V28`/`V60`/`V65`)
and cross-referenced every `UNIQUE` constraint/index in the schema.

| Table | Unique constraint | Tenant-safe? | Why |
|---|---|---|---|
| `tenant_monthly_usage` | `(tenant_id, period)` | ✅ | tenant_id in key |
| `services` | `(tenant_id, project_id, name)` | ✅ | tenant_id in key |
| `idempotency_keys` | `(idempotency_key, tenant_id, resource_type)` | ✅ | tenant_id in key |
| `incident_events` | `(incident_id, sequence)` | ✅ | `incident_id` is a global UUID PK, already unique per-tenant by construction |
| `deduplicated_alerts` | `(tenant_id, fingerprint)` | ✅ | tenant_id in key |
| `escalation_policies` | `(tenant_id, COALESCE(service_id,''))` | ✅ | tenant_id in key |
| `war_rooms` | `(incident_id)` partial | ✅ | same reasoning as incident_events |
| `user_calendar_connections` | `(user_id, provider)` | ✅ | `user_id` belongs to exactly one tenant |
| `tenant_branding`/`tenant_sso_config`/`tenant_cost_settings` | `(tenant_id)` | ✅ | trivially tenant-exclusive |
| `tenant_ticket_integrations`/`inbound_webhook_signing_secrets` | `(tenant_id, provider)` | ✅ | tenant_id in key |
| **`push_device_tokens`** | **`(expo_push_token)`** | ❌ | **the bug — fixed below** |
| **`status_pages`** | **`(slug)`**, separately from `(tenant_id)` | ❌ | **same shape — fixed below, differently** |

Every other RLS-protected table (`ai_analyses`, `alert_suppression_rules`, `audit_logs`,
`escalation_executions`, `evidence_items`, `incidents`, `incident_reports`,
`incident_templates`, `log_events`, `oncall_rotation`, `postmortem_drafts`, `projects`,
`runbook_suggestions`, `status_page_incident_updates`, `usage_records`, `webhook_endpoints`,
`launchdarkly_flag_events`, `incident_cost_entries`, `incident_embeddings`) has no unique
constraint beyond its PK — not exposed to this shape.

### An adjacent, more severe discovery — reported, NOT fixed (explicit instruction)

`StatusPageService.resolveTenantIdForSlug`'s javadoc claims: *"status_pages carries no RLS
policy... safe to call before TenantContext is set."* True when `V49` created the table; **false
since `V60` added `FORCE ROW LEVEL SECURITY` to `status_pages`**, and nothing was updated.

**1. Mechanism, confirmed by code trace:** `PublicStatusPageController.get()` calls
`resolveTenantIdForSlug(slug)` before `TenantContext.set(...)`. At that point
`current_setting('app.tenant_id', true)` is unset → `NULLIF(..., '')::uuid` is `NULL` → the
policy `tenant_id = NULL` is never `TRUE` for any row (SQL `NULL` comparison semantics) →
`findBySlug` returns empty for every slug, unconditionally. **The entire public status page
endpoint currently 404s for every valid slug**, permanently, in any environment where the app
connects as a non-bypass-RLS role — which is how this application actually connects (see
`AbstractIntegrationTest`'s own comment: *"App runtime connects as a non-superuser role so RLS...
is actually enforced"*).

**2. Test coverage — the question flagged as most important.** `StatusPageServiceTest` is the
only test file touching this path, and it is a pure Mockito unit test —
`@Mock private StatusPageRepository statusPageRepository`. Every stub
(`when(statusPageRepository.findBySlug("acme")).thenReturn(Optional.of(page()))`) returns
canned data unconditionally; it never touches Postgres, RLS, or `TenantContext` at all. **This
bug has zero test coverage that could ever have caught it** — not because the test harness
bypasses RLS (it deliberately doesn't, precisely so RLS is enforced in integration tests), but
because no integration test exists for `PublicStatusPageController`/`resolveTenantIdForSlug` in
the first place. A Mockito-mocked repository is structurally incapable of proving anything about
RLS behavior either way — this is a real, general gap: any code path whose correctness depends
on RLS is unverified unless it's covered by a real Testcontainers-backed test, and this one
wasn't.

**3. Grep for the same stale-assumption shape elsewhere.** Searched all of `src/main/java` for
RLS-related comments (`no RLS`, `not RLS`, `carries no`, `safe to call before`, etc.) — the only
hits are the two files above (`StatusPageService.java`/`PublicStatusPageController.java`), both
describing the same one incident. No other stale "this table has no RLS" assumption exists.

**Not fixed in this pass, per explicit instruction.**

---

## Fix 1: `push_device_tokens` — composite `(tenant_id, expo_push_token)` key

**Semantics confirmed before implementing, as required.** Can one physical device legitimately
serve two tenants? Yes — this product's own on-call model supports an SRE/consultant on-call for
two different client orgs on the same phone, wanting pushes from both tenants' incidents. So the
right invariant is "one token can be registered once per tenant," not "last write wins,
evict the older registration." A composite key expresses exactly that.

### Changes

- **`V67__scope_push_device_token_uniqueness_to_tenant.sql`** (new) — `DROP INDEX
  idx_push_device_tokens_token`, `CREATE UNIQUE INDEX idx_push_device_tokens_tenant_token ON
  push_device_tokens (tenant_id, expo_push_token)`. Safe against existing data: the old
  constraint was strictly global, so no two existing rows could already violate the new, looser
  composite key.
- **`PushDeviceToken.java`** — removed `unique = true` from the `expoPushToken` `@Column`
  (single-column JPA-level `unique` can't express a composite key, and `ddl-auto=validate` never
  reads it for enforcement anyway — the real DB migration is authoritative, consistent with how
  every other composite-unique entity in this codebase, e.g. `WebhookSigningSecret`, is
  annotated).
- **`PushDeviceTokenRepository.java`** — added `findByTenantIdAndExpoPushToken(UUID, String)`.
  `findByExpoPushToken` is left in place — existing tests use it directly under a real tenant
  context (`withTenant(...)`), where it's harmless.
- **`PushNotificationService.registerToken`** — looks up by `findByTenantIdAndExpoPushToken`
  instead of the bare token, so the SELECT half of the upsert is scoped exactly the way RLS
  already scopes it. Same-tenant re-registration (new user on a shared device, or the same user
  reinstalling) is unchanged — still a normal, correctly-scoped upsert.

No exception-catching was added here (unlike Fix 2) — the composite key means the collision this
bug was about can no longer happen at all; there was no reason to also paper over it with a
catch block.

### Tests, per your spec (BY NAME, confirmed from surefire XML)

`TEST-com.aetherops.push.PushNotificationServiceTest.xml` (9, was 8 — updated stubs to the new
lookup method, added 1):
- `registerToken_newToken_savesIt`
- `registerToken_existingToken_updatesUserAndTenant`
- `registerToken_sameTokenDifferentTenant_isTreatedAsANewRegistration_notTheOtherTenants` — new;
  asserts the lookup is scoped by tenantId, not just the token
- `registerToken_nullPlatform_savesWithNullPlatform`
- `sendPageAlert_noRegisteredDevices_doesNotCallExpo`
- `sendPageAlert_registeredDevices_postsToExpoForEachToken`
- `sendPageAlert_expoUnreachable_doesNotThrow`
- `sendPageAlert_messageBodyIncludesAllRegisteredTokens`
- `sendPageAlert_messagesUseHighPriorityAndDefaultSound`

`TEST-com.aetherops.push.PushTokenIntegrationTest.xml` (5, was 3 — added 2, real Postgres/RLS):
- `register_persistsUnderTheCallersOwnIdentity`
- `register_smuggledUserIdAndTenantIdInBody_areSilentlyIgnored_notHonored`
- `register_oldUserIdPathVariable_noLongerExists_returns404`
- `register_sameExpoPushToken_twoDifferentTenants_bothSucceed` — new; **the exact previously-500
  scenario** — proves both registrations now succeed and coexist as separate rows
- `register_sameTenantSameToken_reRegistering_isIdempotentUpdate_notDuplicateRow` — new; proves
  the composite key didn't turn ordinary same-tenant re-registration into a duplicate row
  (asserts exactly 1 row after two registrations, fields reflecting the second)

---

## Fix 2: `status_pages.slug` — clean 409 via caught `DataIntegrityViolationException`

**Semantics are different from push tokens, and the fix is different in kind.** A status page
slug appears in a public URL (`GET /api/v1/status-pages/{slug}`) — it must stay globally unique;
two tenants cannot both own the same slug. So this is **not** a composite-key relaxation; the
global `idx_status_pages_slug` index is correct and untouched. The bug is purely in the
application code's pre-check.

**Chosen approach: catch `DataIntegrityViolationException`, not a cross-tenant-visible existence
check.** You asked me to report which I picked and why: a native/RLS-bypassing existence check
would let tenant B learn whether tenant A owns a given slug without ever attempting to claim it
(an enumeration/information-leak surface, however small); a 409 on an actual claim attempt leaks
the identical fact (the slug is taken) but only to a tenant that already committed to trying that
exact slug, and it requires no new DB-privilege plumbing or bypass-role wiring. Since both leak
the same one bit and the caught-exception approach is simpler and needs no extra privilege, I
used it.

### The old code, and what was actually wrong with it

```java
if (!statusPageRepository.existsBySlug(request.getSlug())
        && statusPageRepository.findByTenantId(tenantId).isEmpty()) {
    // create
}
StatusPage existing = statusPageRepository.findByTenantId(tenantId)
    .orElseThrow(() -> new IllegalStateException("Slug '" + request.getSlug() + "' is already taken"));
// update
```
`existsBySlug` runs under the caller's own RLS scope — it can never see another tenant's slug,
so it always reports a taken slug as available, letting the INSERT proceed and collide with the
(RLS-independent) unique index anyway. **A second, smaller bug was found in the same method
while fixing this:** the `IllegalStateException` thrown above has **no handler in
`GlobalExceptionHandler`** — it was already falling through to the generic `Exception.class`
handler and returning a 500 even in the one case the original author clearly intended to be a
clean error. Both are fixed by the same rewrite.

### The fix

```java
@Transactional
public StatusPage configure(UUID tenantId, CreateStatusPageRequest request) {
    Optional<StatusPage> ownPage = statusPageRepository.findByTenantId(tenantId);
    try {
        if (ownPage.isPresent()) {
            StatusPage existing = ownPage.get();
            existing.setName(request.getName());
            existing.setSlug(request.getSlug());
            return statusPageRepository.saveAndFlush(existing);
        }
        return statusPageRepository.saveAndFlush(StatusPage.builder()
            .tenantId(tenantId).slug(request.getSlug()).name(request.getName()).build());
    } catch (DataIntegrityViolationException e) {
        throw new SlugAlreadyTakenException(request.getSlug());
    }
}
```
- `existsBySlug` is gone from the decision path entirely (and removed from
  `StatusPageRepository`, now unused) — the database constraint is the sole source of truth,
  checked by actually attempting the write rather than guessing first via a query that can't see
  the whole table.
- **`saveAndFlush`, not `save`, is required.** Hibernate can defer a plain `save()`'s
  INSERT/UPDATE until transaction commit — which happens *after* this method (and its
  `try`/`catch`) has already returned, so the exception would escape the catch entirely and
  still 500. `saveAndFlush` forces the SQL to run synchronously inside the `try`.
- New `SlugAlreadyTakenException` (`com.aetherops.common.exception`, mirrors
  `DuplicateEmailException`'s shape exactly) + a `GlobalExceptionHandler` entry mapping it to
  `409 CONFLICT` — this is also what makes the pre-existing "own page, slug taken" branch
  correctly return 409 for the first time.
- Every case in the original method's matrix ends up handled the same way, now reliably: new
  slug already owned by someone else → create attempt fails → caught → 409. Existing page's
  slug changed to one someone else owns → update attempt fails → caught → 409 (previously this
  path had **no** exception handling at all and would 500 outright). Own slug unchanged, or a
  genuinely free slug → succeeds exactly as before.

### Tests, per your spec (BY NAME, confirmed from surefire XML)

`TEST-com.aetherops.statuspage.service.StatusPageServiceTest.xml` (15, was 12 — removed the
stale `existsBySlug` stub since nothing calls it now, switched `save` stubs to `saveAndFlush`,
added 3):
- `configure_newTenant_createsPage`
- `configure_existingTenantPage_updatesInPlace` — new
- `configure_newTenant_slugAlreadyOwnedByAnotherTenant_throwsSlugAlreadyTaken_notDataIntegrityViolation`
  — new; mocks `saveAndFlush` to throw `DataIntegrityViolationException`, asserts the service
  translates it to `SlugAlreadyTakenException`, never lets the raw DB exception escape
- `configure_existingTenantPage_slugChangedToOneOwnedByAnotherTenant_throwsSlugAlreadyTaken` —
  new; the update-path collision the original code had zero handling for at all
- `resolveTenantIdForSlug_unknownSlug_throwsResourceNotFound`
- `postUpdate_unknownIncident_throwsResourceNotFound`
- `postUpdate_incidentFromDifferentTenant_throwsResourceNotFound`
- `postUpdate_validIncident_savesUpdate`
- `getPublicPage_unknownSlug_throwsResourceNotFound`
- `getPublicPage_noIncidentUpdates_allComponentsOperational`
- `getPublicPage_openCriticalIncidentWithUpdate_marksComponentAsMajorOutage`
- `getPublicPage_resolvedIncident_doesNotAffectComponentStatus`
- `getPublicPage_incidentWithoutAnyUpdate_neverAppearsPublicly`
- `getPublicPage_noBrandingConfigured_brandingIsNull`
- `getPublicPage_brandingConfigured_includesItInResponse`

`TEST-com.aetherops.statuspage.StatusPageIntegrationTest.xml` (2, new file — real Postgres/RLS,
not mocked, precisely because question 2 above established that a mocked repository can prove
nothing about this bug shape):
- `configure_secondTenantClaimsSlugOwnedByAnotherTenant_getsCleanConflict_notServerError` — the
  exact previously-500 scenario against a real database; asserts `409` and the clean error
  message, not an uncaught exception. (Surefire log shows Hibernate logging the underlying SQL
  `ERROR: duplicate key value violates unique constraint "idx_status_pages_slug"` at the moment
  it's thrown and caught — expected noise, not a test failure.)
- `configure_ownExistingSlugUnchanged_stillSucceeds` — reconfiguring with the same slug a tenant
  already owns must not be mistaken for a collision

Narrow run at implementation time —
`mvn test -Dtest=PushNotificationServiceTest,PushTokenIntegrationTest,StatusPageServiceTest,StatusPageIntegrationTest`
→ **Tests run: 31, Failures: 0, Errors: 0, Skipped: 0** (9 + 5 + 15 + 2).

---

## Fix 3: the dead public status page (previously reported, NOT fixed — now fixed)

### Recon, round 2 — re-confirmed and extended

**Mechanism, confirmed by code trace (unchanged from the earlier report):**
`PublicStatusPageController.get()` calls `resolveTenantIdForSlug(slug)` before
`TenantContext.set(...)`. With `app.tenant_id` unset, `NULLIF(..., '')::uuid` is `NULL`, and
`tenant_id = NULL` is never `TRUE` in SQL — `findBySlug` matched zero rows for every slug,
unconditionally, ever since `V60` added `FORCE ROW LEVEL SECURITY` to `status_pages`.

**What the public path actually reads, field by field** (traced every line of `getPublicPage`):
- `status_pages.findBySlug` → only `tenantId`, `name` used.
- `status_page_incident_updates.findTop50ByTenantId...` → only `status`, `message`, `postedAt`
  — curated, customer-facing by construction (an incident is invisible here until someone
  explicitly posts an update for it).
- `incidents.findAllById(...)` — the **full** `Incident` row is loaded into memory, but only
  `title`, `status`, `severity`, `serviceId` are ever read. Never copied into the response:
  `resolutionNotes` (internal, postmortem-adjacent text), `slackChannelId`/`slackThreadTs`,
  `tags`, `description`, `acknowledgedAt`, both SLA-breach flags, `correlationGroupId`.
  Confirmed structurally, not just by inspection: `PublicStatusPageResponse`'s DTO has no field
  to put any of them in even if the mapper tried.
- `services.findByTenantId` → only `.getName()`; `ownerTeam` is loaded but never exposed.
- `tenant_branding` → all four fields it has are meant to be public; nothing extra exists on
  that table.
- Responder identity and postmortems live in tables this path never queries at all
  (`IncidentTimeline`, `postmortem_drafts`) — structurally can't leak from here.
- **Correct today, not guaranteed tomorrow:** nothing stops a future edit from adding e.g.
  `.description(incident.getDescription())` to the mapper. Flagging, not fixing — out of scope.

**Test coverage gap, re-confirmed:** the only prior test touching this path
(`StatusPageServiceTest`) mocks `StatusPageRepository` entirely — every stub returns canned data
regardless of `TenantContext` or real RLS. This bug had zero coverage that could ever have
caught it, not because the test *harness* bypasses RLS (it deliberately doesn't — see below) but
because no integration test existed for this path in the first place. Fixed by adding
`PublicStatusPageIntegrationTest` (real Postgres, real RLS — see Tests below).

**Database role, answered separately for test and production, per your explicit instruction not
to assume they match:**

*Test:* role `app_user`, created by `src/test/resources/create-app-role.sql`:
```sql
CREATE ROLE app_user WITH LOGIN PASSWORD 'apppass';
```
No `SUPERUSER`, no `BYPASSRLS` — neither is granted here or anywhere else in the repo (grepped
every `.sql`/`.yaml`/`.yml`/`.md`/`.sh` file for `BYPASSRLS`/`SUPERUSER`/`CREATEROLE`; the only
hits are comments, never a grant). This is what the application itself connects as in tests:
```java
// AbstractIntegrationTest.java:115-118
// App runtime connects as a non-superuser role so RLS (which superusers/BYPASSRLS...
// ...Migrations still run as the superuser "test" role, separately below.
registry.add("spring.datasource.username", () -> "app_user");
```
Migrations run separately, as `"test"` — Testcontainers' bootstrap `POSTGRES_USER`, which the
official `postgres` image always makes a superuser. **Test's app role has neither attribute —
RLS is genuinely exercised in the suite.**

*Production: cannot be verified from this repository — stated plainly rather than guessed.*
Three deployment paths exist and disagree:
- `kubernetes/secret.yaml` documents intent (*"principle of least privilege — do not use the
  superuser account"*) but enforces nothing — there is no `CREATE ROLE ... NOSUPERUSER
  NOBYPASSRLS` script for this path the way `create-app-role.sql` exists for tests.
- `docker-compose.yml` sets `DB_USERNAME: ${POSTGRES_USER:-aetherops_user}` — **the same
  variable used to create the Postgres container's bootstrap superuser.** Checked whether this
  wiring actually works: `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` don't match Spring's
  relaxed-binding convention (`SPRING_DATASOURCE_*`), and no translation layer exists (`Dockerfile`'s
  `ENTRYPOINT` is a bare `JarLauncher`, no wrapper script; no `application-docker.yml`) — this
  file most likely doesn't configure the datasource at all and is stale, but if it or something
  like it ever did work as written, the app would run AS the Postgres superuser: stronger than
  BYPASSRLS, a total bypass of every RLS policy in the codebase.
- `railway.toml` (the file `application.yml` itself calls "the live deployment target") only
  configures build/healthcheck/restart behavior — no env vars, no role info. Railway's actual
  project variables live outside this repository.

**Direct answer:** I cannot rule out that production runs with `BYPASSRLS` or as a superuser.
The repo documents an intent against it and enforces it nowhere; one of its three deployment
paths would grant full superuser access if it worked at all. If Railway's real provisioning
matches the documented intent, RLS is real in production. If it doesn't, every RLS policy in
this codebase — including the one this fix just added — provides no protection beyond whatever
the application layer already enforces, and that can only be confirmed by inspecting Railway's
actual project configuration, which is outside this repository.

### The fix

Your proposed shape (one narrow mechanism, resolve `tenant_id` only, nothing else) is right, but
a plain `SECURITY DEFINER` function wouldn't work: `FORCE ROW LEVEL SECURITY` means even a
function owned by the table owner is still subject to RLS — making it actually bypass would need
the owner to hold `BYPASSRLS`, i.e. a new role, exactly what you said not to introduce.

Implemented instead as a **second, `SELECT`-only permissive RLS policy**, visible only while a
transaction-local flag is set — and the only thing that ever sets that flag is one PL/pgSQL
function (`V68__public_status_page_slug_resolution.sql`):
```sql
CREATE POLICY slug_lookup_bypass ON status_pages FOR SELECT
    USING (current_setting('app.allow_status_page_slug_lookup', true) = 'true');

CREATE FUNCTION resolve_status_page_tenant(p_slug VARCHAR) RETURNS UUID
LANGUAGE plpgsql AS $$
DECLARE result UUID;
BEGIN
    PERFORM set_config('app.allow_status_page_slug_lookup', 'true', true);
    SELECT tenant_id INTO result FROM status_pages WHERE slug = p_slug;
    PERFORM set_config('app.allow_status_page_slug_lookup', 'false', true);
    RETURN result;
EXCEPTION WHEN OTHERS THEN
    PERFORM set_config('app.allow_status_page_slug_lookup', 'false', true);
    RAISE;
END; $$;
```
Postgres OR's multiple permissive policies together, so this adds visibility without touching
or weakening `tenant_isolation` — every other query against `status_pages` (i.e. every query
except this one function's own `SELECT`, since nothing else ever sets the flag) is completely
unaffected. No new database role, no `BYPASSRLS` grant anywhere — needs nothing beyond the
ordinary `CREATE POLICY`/`CREATE FUNCTION` privilege the table's owner already has.

**Addition: the `EXCEPTION` block, per your review.** My first version reset the flag only after
the `SELECT`, relying on `is_local = true` reverting it automatically once the enclosing
transaction aborts if something threw instead. You're right that's incidental, not guaranteed by
the function itself — it depends on the caller actually letting the whole transaction abort
rather than, say, catching the error some other way and continuing. The `EXCEPTION WHEN OTHERS`
block now resets the flag on the error path explicitly, then `RAISE`s so the original error still
propagates to the caller unchanged — the reset no longer depends on anything outside this
function's own control flow.

**Addition: a comment on the policy warning against copying the pattern**, per your request —
both a `COMMENT ON POLICY` (queryable from the database itself, e.g. via `\d+ status_pages` or
`pg_policies`) and expanded prose directly above the `CREATE POLICY` statement in the migration,
explaining exactly why this is safe here (the one column it exposes, `tenant_id` keyed by an
already-public slug, isn't sensitive on its own) and explicitly warning it must not be replicated
on a table where cross-tenant `SELECT` visibility would actually matter.

Wired in via a native-query repository method (`StatusPageRepository.resolveTenantIdBySlugBypassingRls`),
called from `StatusPageService.resolveTenantIdForSlug`. **The stale, false comment — *"status_pages
carries no RLS policy... safe to call before TenantContext is set"* — is deleted**, not softened;
replaced with an accurate description of the actual mechanism now in place. The matching stale
comment in `PublicStatusPageController` is corrected the same way.

### Tests, per your spec (BY NAME, confirmed from surefire XML) — real database, not mocked

`PublicStatusPageIntegrationTest` (5 — deliberately never calls `asTenant(...)` anywhere in the
file, simulating a genuinely anonymous visitor with no JWT and no tenant context at request
time, which is exactly the condition that broke this endpoint):
- `get_validSlug_noTenantContextAtRequestTime_returnsCorrectTenantsPage` — the core regression
  case: a real slug resolves correctly with zero tenant context set at request time
- `get_unknownSlug_returns404`
- `get_tenantASlug_neverReturnsTenantBData` — two tenants, each with their own status page and
  incident; tenant A's slug returns only tenant A's data
- `get_publicResponse_containsNoInternalOnlyFields` — asserts the raw response body contains
  none of `resolutionNotes`, `slackChannelId`, `slackThreadTs`, `tenantId`, `ownerTeam`,
  `fingerprint`, `eventCount`, `acknowledgedAt`, `correlationGroupId` — every internal-only field
  identified in the recon above, checked directly against the actual serialized JSON rather than
  trusted from reading the DTO mapping code alone
- `resolveTenantIdBySlugBypassingRls_doesNotLeakVisibility_intoSubsequentQueryInSameTransaction`
  — new, added per your review; **this is the test that actually proves the flag reset works**,
  not merely that the whole suite passes. Both calls run inside ONE transaction
  (`withTenant`'s single `@Transactional` boundary), with `TenantContext` set to tenant A
  throughout: first the bypass resolves tenant B's slug (asserted — that's the function's whole
  job), then an ordinary, RLS-governed `findByTenantId(tenantB)` runs in the *same* transaction,
  still under tenant A's context, and is asserted `empty`. Without this test, a future edit that
  deleted the reset line (either the success-path one or the new `EXCEPTION`-block one) would
  pass the entire rest of the suite undetected.

`StatusPageServiceTest.xml` (+1, was 15, now 16):
- `resolveTenantIdForSlug_knownSlug_returnsTenantId` — new; the mocked-repository counterpart,
  kept alongside the real integration tests above rather than replacing them

Narrow run at implementation time —
`mvn test -Dtest=PublicStatusPageIntegrationTest,StatusPageServiceTest,StatusPageIntegrationTest`
→ **Tests run: 23, Failures: 0, Errors: 0, Skipped: 0** (5 + 16 + 2).

---

## Full test run

Docker Desktop confirmed running before every run in this step. Three full-suite runs happened:
after Fixes 1+2 (943), after Fix 3's initial implementation (948), and after the
EXCEPTION-block/leak-test hardening below (949, current/final).

```
mvn test
...
[INFO] Tests run: 949, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**949 tests, 0 failures, 0 errors, 0 skipped.** (943 after Fixes 1+2 + 6 new for Fix 3: 5 in
`PublicStatusPageIntegrationTest` (4 from the initial implementation +
`resolveTenantIdBySlugBypassingRls_doesNotLeakVisibility_intoSubsequentQueryInSameTransaction`
from this review round) + 1 in `StatusPageServiceTest` — matches exactly.)

No test was skipped, disabled, or weakened to get here.

---

## Files changed this step

New:
- `src/main/resources/db/migration/V67__scope_push_device_token_uniqueness_to_tenant.sql`
- `src/main/java/com/aetherops/common/exception/SlugAlreadyTakenException.java`
- `src/test/java/com/aetherops/statuspage/StatusPageIntegrationTest.java`

Modified:
- `src/main/java/com/aetherops/push/PushDeviceToken.java` — removed single-column `unique=true`
- `src/main/java/com/aetherops/push/PushDeviceTokenRepository.java` — added
  `findByTenantIdAndExpoPushToken`
- `src/main/java/com/aetherops/push/PushNotificationService.java` — upsert lookup now
  tenant-scoped
- `src/main/java/com/aetherops/common/exception/GlobalExceptionHandler.java` — added
  `SlugAlreadyTakenException` → 409 handler
- `src/main/java/com/aetherops/statuspage/service/StatusPageService.java` — `configure(...)`
  rewritten: DB constraint is the sole source of truth, `saveAndFlush` + caught
  `DataIntegrityViolationException` → `SlugAlreadyTakenException`
- `src/main/java/com/aetherops/statuspage/repository/StatusPageRepository.java` — removed unused
  `existsBySlug`
- `src/test/java/com/aetherops/push/PushNotificationServiceTest.java` — updated stubs to
  `findByTenantIdAndExpoPushToken`, added 1 test
- `src/test/java/com/aetherops/push/PushTokenIntegrationTest.java` — added 2 tests
- `src/test/java/com/aetherops/statuspage/service/StatusPageServiceTest.java` — removed stale
  stub, `save`→`saveAndFlush`, added 3 tests (then +1 more in Fix 3, see below)

Fix 3 (public status page):
- `src/main/resources/db/migration/V68__public_status_page_slug_resolution.sql` — new
- `src/test/java/com/aetherops/statuspage/PublicStatusPageIntegrationTest.java` — new
- `src/main/java/com/aetherops/statuspage/repository/StatusPageRepository.java` — added
  `resolveTenantIdBySlugBypassingRls` native query
- `src/main/java/com/aetherops/statuspage/service/StatusPageService.java` —
  `resolveTenantIdForSlug` rewritten to use it; stale/false RLS comment deleted, replaced with an
  accurate one
- `src/main/java/com/aetherops/statuspage/controller/PublicStatusPageController.java` —
  matching stale comment corrected
- `src/test/java/com/aetherops/statuspage/service/StatusPageServiceTest.java` — updated the
  `resolveTenantIdForSlug_unknownSlug_...` stub to the new method, added
  `resolveTenantIdForSlug_knownSlug_returnsTenantId`

Nothing left unfixed from this step — all three findings (push token collision, status-page-slug
collision, dead public status page) are now implemented and tested.
