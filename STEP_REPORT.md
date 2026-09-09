# Phase 1 Remediation — Step D: Tenant Isolation, Defence in Depth

Closes audit finding 4. D0 (recon) approved; D1 (fix) is now implemented, per go-ahead. `PushTokenController` (finding 7) stayed out of scope for Step D throughout — belongs to Step E. **Nothing committed.**

## Addendum 1 — verification requested before D1 approval

**One test was added** (`src/test/java/com/aetherops/tenant/TenantContextOrderingTest.java`) to empirically settle the D0(b) uncertainty about AOP ordering, rather than leave it resting on documented-but-unverified behavior. Details below, after the re-printed (a)/(b) sections.

## Addendum 2 — D1 implementation

### What changed and why

**Primary control — service/repository layer:**
- `IncidentRepository` — added `Optional<Incident> findByIdAndTenantId(UUID id, UUID tenantId)`. The predicate now lives in the query itself, not a `.filter()` a future caller could omit.
- `IncidentService.findById`, `.update`, `.softDelete` — all three now call `incidentRepository.findByIdAndTenantId(id, TenantContext.get())` instead of a bare `findById(id)`. `TenantContext.get()` is the caller's own JWT-derived tenant (already populated per-request by the existing filter chain — no new input needed). A not-found ID and a wrong-tenant ID both produce an empty `Optional` from the same query, so both paths hit the exact same `orElseThrow(() -> new ResourceNotFoundException(...))` — **there is no branch that could return a different status for "doesn't exist" vs "exists, wrong tenant."** Confirmed by the `.filter(...)` alternative being structurally impossible here (there's nothing to filter after the fact — the query itself returns nothing).
- This automatically closes `IncidentCommandController.resolve` too: it calls `IncidentCommandService.handle(ResolveIncidentCommand)` → `incidentService.update(...)`, the same method. No change was needed in the command controller or service — confirmed by `resolveCommand_otherTenantsIncident_returns404_notForbidden` below.

**Secondary control — controller layer (defense in depth, as instructed):**
- `IncidentController.getById`, `.update`, `.delete` now each take a `tenantId` query param and call `TenantContext.requireOwn(tenantId)` before delegating to the service, matching the pattern every other `IncidentController` endpoint already uses. This is explicitly *not* the primary control — it only proves the caller's own tenant claim is genuine; it cannot and does not prove the target incident belongs to that tenant (that's `findByIdAndTenantId`'s job). `requireOwn`'s `ForbiddenException` (403) fires only on a self-consistency failure (caller's tenant param doesn't match their JWT) — never on "this incident belongs to someone else," which is always a 404 from the service layer. These two failure modes stay on separate, non-conflatable code paths, so a wrong-tenant incident ID can never surface as a 403.
- `IncidentCommandController.resolve` already had `requireOwn` from Step B — unchanged.

**`SlackWorkspaceRepository.findByTenantId` — traced, all 3 call sites guarded, no fix needed:**
- `PostmortemService.fetchSlackTranscript` — `tenantId` arrives already validated: `PostmortemService.generate`/`.getLatest` are only reachable via `IncidentController`, which calls `TenantContext.requireOwn(tenantId)` before invoking them.
- `OnboardingStatusService.getStatus` — same: `OnboardingController.status` calls `requireOwn(id)` first.
- `EscalationService.notifySlack` — uses `incident.getTenantId()`, the already-loaded entity's own field, not any caller-supplied value at all. Safe by construction regardless of how the incident was loaded.

### Tests

All Mockito-unit and MockMvc-integration tests below assert **404, never 403**, for the cross-tenant case, per instruction.

**New — `IncidentServiceTest.java`** (pure Mockito, no Spring, no database, no RLS — proves the app check alone suffices):
- `findById_ownTenant_returnsIncident`
- `findById_otherTenantsIncident_throwsNotFound_notForbidden`
- `update_ownTenant_appliesChange`
- `update_otherTenantsIncident_throwsNotFound_notForbidden`

**Extended — `IncidentSoftDeleteTest.java`** (existing file, fixed for the new repository signature, one test added):
- `softDelete_setsDeletedAt` (fixed)
- `softDelete_throwsWhenNotFound` (fixed)
- `softDelete_otherTenantsIncident_throwsNotFound_notForbidden` (new — same independent-of-RLS proof as above)

**New — `IncidentTenantIsolationIntegrationTest.java`** (full-stack MockMvc, real Postgres, real RLS — proves the *whole* stack agrees, not just the unit-level app check):
- `getById_ownIncident_returns200`
- `getById_otherTenantsIncident_returns404_notForbidden`
- `update_ownIncident_returns200`
- `update_otherTenantsIncident_returns404_notForbidden`
- `delete_ownIncident_returns204`
- `delete_otherTenantsIncident_returns404_notForbidden` (also confirms the incident was NOT silently deleted despite the 404)
- `resolveCommand_ownIncident_returns200`
- `resolveCommand_otherTenantsIncident_returns404_notForbidden` (the `IncidentCommandController` downstream case)

**Fixed (broken by the repository signature change, not new coverage):** `IncidentCreationEnrichmentTest.java` (7 stubs updated to `findByIdAndTenantId` + `TenantContext.set`), `IdempotencyKeyTest.java` and `IncidentUsageLimitTest.java` (both hit `IncidentService.create`'s idempotency-replay path, which internally calls the now-tenant-scoped `findById` — an incidental but correct tightening: idempotent-replay responses are now also tenant-scoped), `SlackAlertIntegrationTest.java` (2 `PATCH` calls needed the now-required `tenantId` param added).

### Full `mvn test`

```
Tests run: 879, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
(866 prior + 13 new: 4 in `IncidentServiceTest` + 1 in `IncidentSoftDeleteTest` + 8 in `IncidentTenantIsolationIntegrationTest`. Docker Desktop was running throughout.)

### New tests confirmed by name, from surefire XML (all `errors="0" failures="0"`)

- `TEST-com.aetherops.incident.service.IncidentServiceTest.xml` (4/4): `findById_ownTenant_returnsIncident`, `findById_otherTenantsIncident_throwsNotFound_notForbidden`, `update_ownTenant_appliesChange`, `update_otherTenantsIncident_throwsNotFound_notForbidden`
- `TEST-com.aetherops.incident.IncidentSoftDeleteTest.xml` (3/3, includes 1 new): `softDelete_setsDeletedAt`, `softDelete_throwsWhenNotFound`, `softDelete_otherTenantsIncident_throwsNotFound_notForbidden`
- `TEST-com.aetherops.incident.IncidentTenantIsolationIntegrationTest.xml` (8/8): `getById_ownIncident_returns200`, `getById_otherTenantsIncident_returns404_notForbidden`, `update_ownIncident_returns200`, `update_otherTenantsIncident_returns404_notForbidden`, `delete_ownIncident_returns204`, `delete_otherTenantsIncident_returns404_notForbidden`, `resolveCommand_ownIncident_returns200`, `resolveCommand_otherTenantsIncident_returns404_notForbidden`
- Also re-confirmed passing after the signature-change fixes: `TEST-com.aetherops.incident.service.IncidentCreationEnrichmentTest.xml` (20/20), `TEST-com.aetherops.incident.IdempotencyKeyTest.xml` (2/2), `TEST-com.aetherops.incident.IncidentUsageLimitTest.xml` (2/2), `TEST-com.aetherops.slack.SlackAlertIntegrationTest.xml` (9/9)

### Note

`IncidentController.getById`/`.update`/`.delete` now require a `tenantId` query param (previously absent). Checked `dashboard.html`/`index.html` — neither calls these three endpoints, so no frontend break. Flagging since it's a public API surface change, even though nothing in this codebase's frontend was affected.

No test was skipped, disabled, or weakened. Nothing outside D1's stated scope was touched. Nothing committed.

---

## D0(a) — Controller endpoints that load a resource by ID and rely solely on RLS

Every controller in the codebase was checked (38 controllers). The overwhelming majority follow one of two safe patterns consistently:
1. `TenantContext.requireOwn(tenantId)` at the top of the method, **and** the service/repository layer also cross-checks the loaded entity's own `tenantId` (e.g. `repository.findById(x).filter(e -> e.getTenantId().equals(tenantId))`, or a repository method like `findTopByIncidentIdAndTenantId...`).
2. For user-owned (not tenant-owned) resources: a `requireSelf(userId)` check plus an entity-level ownership filter (e.g. `CalendarConnectionController.disconnect`).

I verified this pattern holds for: `WarRoomController`/`WarRoomService`, `ProjectController`/`ApiKeyService` (`ApiKeyService.list`/`revoke` call `ProjectService.getForTenant`, which filters `p.getTenantId().equals(tenantId)`), `EvidencePackService`, `PostmortemService`, `RunbookService`, `AIAnalysisService` (all delegate to or replicate `EvidencePackService`'s tenant-filtered lookup), `StatusPageController`, `TicketController`, `AlertSuppressionController`, `EscalationPolicyController`, `SsoConfigController`, `BrandingController`, `TenantCostSettingsController`, `AuditLogController`/`AuditLogService`/`AuditLogExportService` (explicit `tenant_id` predicate via `Specification`), `TenantErasureService`/`TenantDataExportService` (explicit parameterized `WHERE tenant_id = ?` on every dynamic query, table list from `TenantScopedSchemaService`), and more.

### Confirmed gaps — no application-layer check, relying entirely on RLS

**`IncidentController`** (`src/main/java/com/aetherops/incident/controller/IncidentController.java`) — exactly the example you named:

| Endpoint | Line | Check present? |
|---|---|---|
| `GET /api/v1/incidents/{id}` (`getById`) | 51-55 | **None.** No `tenantId` parameter exists at all. `return ApiResponse.success(incidentService.findById(id));` |
| `PATCH /api/v1/incidents/{id}` (`update`) | 71-79 | **None.** No `tenantId` parameter. `@PreAuthorize("hasAnyRole('OWNER','ADMIN')")` gates *role*, not *tenant* — an OWNER/ADMIN of tenant A passes this check while acting on tenant B's incident. |
| `DELETE /api/v1/incidents/{id}` (`delete`) | 151-156 | **None.** No `tenantId` parameter. Same role-vs-tenant gap as above. |

The service layer doesn't help: `IncidentService.findById`, `.update`, `.softDelete` (`src/main/java/com/aetherops/incident/service/IncidentService.java:148-152, 170-172, 248-250`) all do a bare `incidentRepository.findById(id)` with **no tenant predicate whatsoever** — no `.filter(...)`, no `findByIdAndTenantId`. Tenant scoping for these three endpoints exists **only** in the Postgres RLS policy on `incidents` (confirmed `FORCE ROW LEVEL SECURITY` + `tenant_isolation` policy, `V28__enable_row_level_security.sql:4,9,13`).

**Downstream consequence — `IncidentCommandController.resolve`** (`POST /api/v1/commands/incidents/{id}/resolve`): this endpoint *does* call `TenantContext.requireOwn(tenantId)`, but that only proves the caller's JWT tenant matches the tenant they claim — it never checks that the **target incident** belongs to that tenant. It calls `IncidentCommandService.handle(ResolveIncidentCommand)`, which calls `incidentService.update(cmd.getIncidentId(), request)` — the same unguarded method above. `cmd.getTenantId()` is used only for event-sourcing/audit logging, never as a query filter. So a caller passing their own valid tenant (satisfies `requireOwn`) plus an arbitrary incident ID from another tenant reaches the identical unguarded `update`. This is arguably worse than the plain `IncidentController` case, since the `requireOwn` call gives a false impression that tenant scoping is handled.

**Dead code, not reachable from any controller (flagging, not counting toward the list above):** `IncidentQueryService.findById` and `IncidentDomainService.{resolve,escalate,reopen}` (`src/main/java/com/aetherops/incident/service/`) have the exact same unguarded-`findById` pattern, but neither class has any caller anywhere in `src/main/java` — confirmed by grep. They appear to be an abandoned parallel CQRS/domain-service implementation. Not in scope for D1 since they're unreachable, but worth knowing they exist in case something gets wired to them later.

**`PushTokenController.register`** (`POST /api/v1/users/{userId}/push-token`) — no `requireSelf`/`requireOwn` check at all; `userId` and `tenantId` are both taken directly from the path/body and written straight into `push_device_tokens` via `PushNotificationService.registerToken`. This was already identified in Step B/C's audit as **finding 7** and explicitly deferred, not this step's scope — repeating it here only because D0(a) asked me to list every ID-based endpoint's check status, and this one has none. Note: `push_device_tokens` **is** RLS-protected (`V60__enable_rls_on_remaining_tenant_tables.sql:42,74,126`) with a policy that has no explicit `WITH CHECK`, so Postgres uses the `USING` clause for both — meaning an INSERT with a forged `tenantId` different from the caller's real `app.tenant_id` should be **rejected by RLS** (raises a policy-violation error, surfaces as a 500 today, not a silent write) — but a forged `userId` **within the caller's own real tenant** is not caught by RLS at all (the policy only checks `tenant_id`), so a user could register a push token misattributed to a teammate's `userId`. Low practical impact today since `PushNotificationService.sendPageAlert` pages every device for the tenant regardless of `userId` (doesn't target by user), but it's a real data-integrity gap.

### Not checked as thoroughly — external/non-JWT auth model

`StripeWebhookController`, `IntegrationsWebhookController` (10 endpoints), `MonitoringWebhookController`, `LaunchDarklyController.webhook`, `SlackInteractivityController`, `SlackCommandController`, `SlackOAuthController`, `PublicStatusPageController` — these authenticate via signature/API-key/deliberately-public access, not a caller JWT, so `TenantContext.requireOwn` doesn't apply the same way (tenant is resolved *from* the credential itself, not asserted by the caller). I did not audit these line-by-line for this pass since the threat model is different (forging a Stripe/Slack signature, not reusing your own valid JWT against someone else's ID) — flagging as **not exhaustively covered**, tell me if you want them in scope too.

---

## D0(b) — RLS session variable lifecycle

**How `app.tenant_id` is set** — `TenantContextInterceptor` (`src/main/java/com/aetherops/tenant/TenantContextInterceptor.java`):
```java
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantContextInterceptor {
    @PersistenceContext
    private EntityManager entityManager;

    @Before("@annotation(org.springframework.transaction.annotation.Transactional)")
    public void propagateTenantContext() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) return;
        try {
            entityManager.createNativeQuery(
                "SELECT set_config('app.tenant_id', :tid, true)"
            ).setParameter("tid", tenantId.toString()).getSingleResult();
        } catch (Exception e) {
            log.warn("Could not propagate tenant context to database session: {}", e.getMessage());
        }
    }
}
```

**Is it reset on connection return, including on the exception path?** There is no explicit reset call anywhere in the codebase (confirmed by grep — no `RESET app.tenant_id`, no `reset_config` call). It doesn't need one: `set_config(..., true)` — the third argument `true` is Postgres's `is_local` flag, equivalent to `SET LOCAL`. A value set this way is **automatically discarded by Postgres itself at the end of the current transaction**, on **both** COMMIT and ROLLBACK — the exception path is covered by Postgres's own transaction semantics, not by any Java code needing to catch it. Since HikariCP (`maximum-pool-size: 10`, confirmed in `application.yml`) only returns a connection to the pool after Spring's transaction manager has fully committed or rolled back the transaction on it, a connection can never be handed to the next borrower mid-transaction with a leftover `app.tenant_id` still active — the GUC is already gone by the time that could happen. **I could not find a code path where this leaks a value across pooled connections.**

**Is it set via `SET` or `SET LOCAL`, and is it inside the transaction?** Via `set_config(..., is_local => true)`, which is `SET LOCAL`'s functional equivalent — transaction-scoped, not session-scoped. It's set inside the transaction: the aspect is `@Before` on any `@Transactional`-annotated method, so by the time it runs, Spring's own transactional advice should already have opened the transaction (see caveat below) — `set_config` with `is_local=true` **requires** an already-open transaction to have any effect (outside a transaction it behaves as a no-op / reverts immediately), so this design is only correct because it reliably runs after the transaction has started.

**The real risk isn't a leak — it's a silent no-op, and it's already documented in the codebase.** `AbstractIntegrationTest.java:51-59`:
> "A bare repository call from test code (e.g. `incidentRepository.findAll()`) does NOT reliably propagate `app.tenant_id` to Postgres on its own — Spring Data's own per-repository transactional advisor is a different object from the one `TenantContextInterceptor`'s aspect reliably wins the ordering tie against for user-defined `@Transactional` service methods — so under enforced RLS it can silently see zero rows even after `TenantContext.set(tenantId)`."

Concretely: `TenantContextInterceptor` is `@Order(Ordered.LOWEST_PRECEDENCE)`. Spring's own `@Transactional` advisor also defaults to `Ordered.LOWEST_PRECEDENCE` unless configured otherwise. Two Spring AOP advisors tied at the same order have **unspecified relative ordering** at a given join point. In practice (per the existing comment, which reads as hard-won operational knowledge, not speculation) it reliably wins the race for **user-defined `@Transactional` service methods** — i.e. every case I found in D0(a)/(c)/(d) — but does **not** reliably win against a **bare Spring Data repository call with no wrapping `@Transactional` service method** (the repository proxy's own internal transactional advisor). All the confirmed-safe patterns I found in this pass (`IncidentService`, `ApiKeyService`, `EvidencePackService`, etc.) are proper `@Transactional` service methods, so this should not affect them. I did not find any controller-reachable code path that calls a repository method with no wrapping `@Transactional` service method — but I did not exhaustively verify every single repository call site either (that's a much larger sweep). Flagging as **not fully verified across the whole codebase**, only confirmed absent in the specific services this pass examined.

**Net assessment:** the mechanism is sound (transaction-scoped `SET LOCAL`-equivalent, auto-resetting, no leak path found). The actual risk is the ordering-tie ambiguity causing a *false negative* (legitimate request silently sees no rows) rather than a cross-tenant *leak* — and that's already a known, documented issue in this codebase, worked around in tests via `TestTransactionHelper`, but with no equivalent production-code guard against a bare repository call being added later without a wrapping `@Transactional` method.

---

## D0(c) — Repository methods using native query / EntityManager / JdbcTemplate / Specification, reachable from a controller

| Method | Explicit tenant predicate? |
|---|---|
| `TenantContextInterceptor.propagateTenantContext` (native query) | N/A — this *is* the tenant-scoping mechanism, not a data query |
| `IncidentService.findAll` → `IncidentSpecification.hasTenant(tenantId)` | **Yes** — explicit `cb.equal(root.get("tenantId"), tenantId)` |
| `AuditLogService.getAuditLogs` (inline `Specification`) | **Yes** — `predicates.add(cb.equal(root.get("tenantId"), tenantId))` |
| `AuditLogExportService.fetchLogs` (inline `Specification`) | **Yes** — same pattern |
| `TenantErasureService.erase` (`JdbcTemplate`, dynamic table list) | **Yes** — every `SELECT COUNT(*)`/`DELETE` is `... WHERE tenant_id = ?` parameterized |
| `TenantDataExportService.export` (`JdbcTemplate`, dynamic table list) | **Yes** — every `SELECT * FROM <table> WHERE tenant_id = ?` parameterized |
| `TenantScopedSchemaService.tenantScopedTables`/`foreignKeyEdges` (`JdbcTemplate`) | N/A — reads `information_schema`/`pg_constraint` metadata, not tenant data; table names are validated against `^[a-zA-Z_][a-zA-Z0-9_]*$` before being concatenated into a query string (belt-and-suspenders since they only ever come from catalog metadata, never request input) |

No native query, `EntityManager`, `JdbcTemplate`, or `Specification` usage reachable from a controller was found **without** an explicit tenant predicate. The `IncidentController` gap in (a) doesn't fall into this bucket — it's a plain `JpaRepository.findById()`, not a native/Specification query; it has no predicate of any kind (explicit or RLS-adjacent), it relies purely on the RLS policy at the database level.

---

## D0(d) — The four non-RLS tables: every reachable query, and its predicate

These four have **no database backstop** — confirmed via `V60__enable_rls_on_remaining_tenant_tables.sql`, which documents the exclusion inline for each:
- `users` (line 7): "Login and registration must look up a row by email alone..."
- `api_keys` (line 13): "ApiKeyAuthFilter resolves a caller's tenant..."
- `refresh_tokens` (line 17): "POST /auth/refresh validates a..."
- `slack_workspaces` (line 21): "Inbound Slack webhooks..."

**`users`** (`UserRepository`):
| Query | Predicate | Reachable from controller? |
|---|---|---|
| `findByEmail(email)` | none (by design — pre-tenant lookup) | Yes, `AuthController.login`/`register` — email is the caller's own claimed identity, not an ID they choose to target someone else |
| `findByGoogleSub(sub)` | none | Yes, `GoogleAuthService` — `sub` comes from a Google-verified token, not attacker-controlled |
| `findByEmailVerificationToken(token)` | none | Yes, `EmailVerificationService` — token is a high-entropy secret, not a guessable ID |
| `findById(userId)` (inherited) | none | `TotpService.verifyAndIssueToken` — `userId` comes from `jwtService.extractUserId(partialToken)`, i.e. the JWT's own subject, not client input; `PasswordResetService.resetPassword` — `userId` comes from the validated reset-token row, not client input; `AuthService.generateTokenForUser` — called only internally after `RefreshTokenService.rotate` already validated the token belongs to that user |
| **No query in `UserRepository` takes a client-supplied arbitrary user ID and returns a row without it being derived from an already-verified credential (JWT subject, Google-verified sub, reset token).** I did not find an IDOR path into `users` from any controller. |

**`api_keys`** (`ApiKeyRepository`):
| Query | Predicate | Reachable from controller? |
|---|---|---|
| `findByProjectIdOrderByCreatedAtDesc(projectId)` | none directly, **but** `ApiKeyService.list` calls `projectService.getForTenant(projectId, tenantId)` first, which throws unless `project.getTenantId().equals(tenantId)` — so by the time this query runs, `projectId` is already proven to belong to the caller's tenant | Yes, `ProjectController.listKeys` |
| `findByKeyHashAndActiveTrue*` variants | by hash (128-bit-plus entropy), not by ID | Yes, `ApiKeyService.resolve` — this is the API-key *authentication* path itself, not a tenant-scoped lookup |
| `findById(keyId)` (inherited, in `revoke`) | `ApiKeyService.revoke` calls `getForTenant` first, then `.filter(k -> projectId.equals(k.getProjectId()))` on the result | Yes, `ProjectController.revokeKey` — **protected** |
| `findById(keyId)` (inherited, in `recordLastUsed`) | none | Not controller-reachable — called internally by `ApiKeyAuthFilter` after `resolve()` already authenticated the key by hash |

**`api_keys` is fully covered** — every controller-reachable path either goes through hash-based lookup or the `getForTenant`+`filter` ownership chain.

**`refresh_tokens`** (`RefreshTokenRepository`, rewritten in Step C):
| Query | Predicate | Reachable from controller? |
|---|---|---|
| `findByTokenHash(hash)` | by SHA-256 hash of the raw token (122 bits of entropy) | Yes, `AuthController.refresh`/`logout` — this is the authentication credential itself, not an ID |
| `deleteAllByUserId(userId)` | by `userId` | `RefreshTokenService.revokeAllUserTokens` — not called from any controller currently (dead per Step C recon: nothing issues refresh tokens end-to-end today) |
| `revokeFamily(familyId)` | by `familyId` | Only called internally from `RefreshTokenService.rotate` after the presented token was already resolved by hash |

**`refresh_tokens` is fully covered** — no query takes an attacker-choosable ID; the token hash itself is the credential.

**`slack_workspaces`** (`SlackWorkspaceRepository`):
| Query | Predicate | Reachable from controller? |
|---|---|---|
| `findByTenantId(tenantId)` | explicit `tenant_id` predicate | Need to confirm every call site checks `requireOwn` first — **not fully traced in this pass**, flagging as unverified |
| `findByTeamId(teamId)` | by Slack's own team ID | Used in Slack OAuth callback / signature-verified webhook flows — different auth model (Slack-signed), not a JWT-bearing controller call an attacker could redirect at another tenant's `teamId` by guessing it (Slack team IDs aren't secrets, but the callback flow is bound to Slack's own OAuth state/signature, not to a value the caller supplies directly) |

**`slack_workspaces`'s `findByTenantId` call sites were not individually traced for a `requireOwn` guard in this pass** — this is the one item in (d) I'd call genuinely incomplete rather than confirmed either way.

---

## Summary of what D1 needs to fix, pending your go-ahead

1. `IncidentController.getById` (GET `/{id}`), `.update` (PATCH `/{id}`), `.delete` (DELETE `/{id}`) — no tenant parameter exists at all today, so fixing this means **adding a `tenantId` parameter** (query param, matching the pattern every other incident endpoint already uses) plus `TenantContext.requireOwn(tenantId)`, **and** changing `IncidentService.findById`/`update`/`softDelete` to filter by tenant (either `findByIdAndTenantId` or a `.filter(...)` check), not just the controller.
2. `IncidentCommandController.resolve` — already calls `requireOwn`, but needs the same service-layer fix as above, since `IncidentService.update` is the shared unguarded method.
3. `SlackWorkspaceRepository.findByTenantId` call sites — need tracing before I can say whether this needs a fix or is already safe.

**`PushTokenController.register` (finding 7) is explicitly OUT of scope for Step D** — confirmed by instruction, belongs to Step E. Not carried into the D1 list above.

## Things I'm unsure about, flagged rather than assumed

- **Resolved by test, see Addendum below**: whether `TenantContextInterceptor` reliably wins its AOP ordering tie against Spring's transactional advisor. Confirmed empirically for `IncidentService.findById` — not re-tested against every `@Transactional` service method in the codebase, so the general claim ("always, everywhere") is narrowed but not eliminated as a residual unknown.
- Whether there's any controller-reachable code path calling a repository method **without** a wrapping `@Transactional` service method (which per (b) would silently get zero rows, not a leak, but I did not do an exhaustive sweep for this pattern).
- The external/signature-authenticated controllers (Slack, Stripe, monitoring webhooks) — not audited to the same depth as JWT-authenticated ones, different threat model.
- `SlackWorkspaceRepository.findByTenantId` call sites, as noted above.

---

## Addendum — D0(b) ordering verification (this message's work)

**Test added:** `src/test/java/com/aetherops/tenant/TenantContextOrderingTest.java` — 2 methods, directly targeting `IncidentService.findById`, which (per (a) above) has **zero application-layer tenant filtering** — RLS via `app.tenant_id` is its only protection, making it the most direct possible probe of the exact property in question.

- `tenantContextInterceptor_setsAppTenantIdBeforeQuery_ownIncidentIsFound` — seeds an incident for tenant A, sets `TenantContext` to tenant A, calls `incidentService.findById(...)` directly (not through MockMvc — this isolates the service-method/AOP/RLS mechanism itself), asserts it's found. If the interceptor lost the ordering race, `app.tenant_id` would be NULL at query time and RLS would filter out even the caller's own incident, turning this into a false 404.
- `tenantContextInterceptor_setsAppTenantIdBeforeQuery_otherTenantIncidentIsNotFound` — same setup but tenant A queries tenant B's incident, asserts `ResourceNotFoundException`. Combined with the first test, this proves `app.tenant_id` is set to *exactly* the calling tenant (not merely "some value" or "everything visible") — RLS is demonstrably active and correctly scoped.

**Result: both PASS.** `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` (confirmed by name from `TEST-com.aetherops.tenant.TenantContextOrderingTest.xml`, `errors="0" failures="0"`). This empirically confirms the documented ordering behavior holds for a real user-defined `@Transactional` service method — per your conditional instruction, since the test did not reveal non-determinism, **no `@Order` change or interceptor rewiring was made.** Making a defensive change to a mechanism a passing test just confirmed works would be a change without a justifying finding.

**Scope of what this proves, stated precisely so it isn't overclaimed:** this confirms the ordering tie resolves correctly for this specific service/method/JVM run, exercised within the same shared Spring test context that the rest of the 866-test suite also runs under (Spring Test caches and reuses application contexts across test classes by default, so this isn't an isolated cold-start result — the same context configuration is exercised repeatedly across the whole suite). It does not re-verify every `@Transactional` service method individually — that residual uncertainty from the original D0(b) list is narrowed, not eliminated.

### Full `mvn test`

```
Tests run: 866, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

(864 prior + 2 new `TenantContextOrderingTest` methods = 866. Docker Desktop was running throughout.)

### New test confirmed by name, from surefire XML

`TEST-com.aetherops.tenant.TenantContextOrderingTest.xml` — `tests="2" errors="0" skipped="0" failures="0"`:
- `tenantContextInterceptor_setsAppTenantIdBeforeQuery_ownIncidentIsFound`
- `tenantContextInterceptor_setsAppTenantIdBeforeQuery_otherTenantIncidentIsNotFound`

Nothing else changed. Nothing committed. Still waiting for your go-ahead on D1.
