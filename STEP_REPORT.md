# Phase 2 Remediation — Step F2: Mass Assignment & Tenant Binding (findings 7, 5)

Status: **Both findings IMPLEMENTED and tested — Finding 7 by removing `plan`/`aiMonthlyBudgetCents`
from `CreateTenantRequest` entirely; Finding 5 by adding the missing `TenantContext.requireOwn()`
call, plus a full controller sweep that found and fixed a second instance of the same gap
(`IncidentControllerV2.list()`).** Nothing committed.

---

## Finding 7 — report first, as instructed: does `aiMonthlyBudgetCents` gate real spend?

Traced every usage of `aiMonthlyBudgetCents` across `src/main/java`. It appeared in exactly 4
places — the request DTO, the `Tenant` entity, the response DTO, and the create/toResponse
mapping in `TenantService` — and was **never read anywhere else**. Not in `AIAnalysisService`,
not in `UsageLimitService`, nowhere in the AI-analysis path. **Purely decorative: write on
create, echo in responses, nothing ever checks it.**

**`plan` is a different story — it gates real spend directly and completely.**
`UsageLimitService.checkAndIncrement` calls `PlanLimits.limitFor(tenant.getPlan(), type)`, and
`PlanLimits.limitFor(Plan.GROWTH, ...)` returns `Integer.MAX_VALUE` for every usage type
(`AI_ANALYSIS`, `RUNBOOK_GENERATION`, `POSTMORTEM_GENERATION`, `INCIDENT_CREATION`) —
**unlimited**. `AIAnalysisService` calls through to `AnthropicAIProvider`, a real Anthropic API
call, on every `AI_ANALYSIS` usage. So: any authenticated user, `POST /api/v1/tenants
{"plan":"GROWTH",...}`, no payment, gets a tenant with genuinely unlimited AI-analysis calls
against real Anthropic API billing. **This is cost abuse, not just a tier-label bypass.**
(Correction to the original audit's exploit example: this codebase's `Plan` enum is `FREE,
STARTER, TEAM, GROWTH` — there is no `ENTERPRISE` value; `GROWTH` is the actual unlimited tier.)

Also confirmed: the legitimate plan-change path already exists, entirely separately.
`SubscriptionService.createCheckoutSession` starts a real Stripe checkout; the plan itself is
updated later via `StripeWebhookController` on confirmed payment. `Tenant.builder()` is
constructed in exactly one place in all of `src/main/java` (`TenantService.create()`), so
removing `plan`/`aiMonthlyBudgetCents` from the signup DTO doesn't touch or break that upgrade
path at all.

**Given this, implemented the primary instruction directly (removal, not clamping) — the
clamp-fallback wasn't needed since the field turned out to be decorative.**

### The fix

- **`CreateTenantRequest`** — `plan` and `aiMonthlyBudgetCents` fields removed entirely (with a
  comment explaining why, referencing this report).
- **`TenantService.create()`** — now hardcodes `.plan(Plan.FREE)` and `.aiMonthlyBudgetCents(0)`
  server-side, unconditionally.
- **Ripple fix, not scope creep:** three internal callers constructed `CreateTenantRequest`
  directly and broke the build — `AuthService`/`GoogleAuthService` (self-registration; both
  already explicitly passed `Plan.FREE, 0` even before this change, so no behavior change there)
  and `TenantOnboardingService.onboard(...)`, which took an explicit `Plan plan` parameter.
  That parameter is unreachable via any HTTP controller today (`OnboardingController` only
  checks onboarding *status*, never calls `onboard(...)`; grepped every caller — only its own
  unit test). Since `TenantService.create()` now always forces `FREE` regardless, keeping that
  parameter would have made it silently ignored — worse than removing it. Removed the `Plan`
  parameter from `onboard(...)` entirely rather than inventing a new "internal plan override"
  capability nobody asked for.

---

## Finding 5 — `IncidentController.create()`, plus a full sweep

### The fix itself

`IncidentController.create()` (`POST /api/v1/incidents`) now calls
`TenantContext.requireOwn(request.getTenantId())` as its first statement, matching
`IncidentCommandController.create()` (the CQRS sibling that already had this check for the
identical kind of resource).

**Traced the actual pre-fix failure mode precisely** (not just "should fail somehow"):
`IncidentService.create()` calls `usageLimitService.checkAndIncrement(request.getTenantId(), ...)`
as its first statement — i.e. the attacker-supplied tenant id — while the real `TenantContext`/
`app.tenant_id` stays the caller's own. `tenant_monthly_usage` has `FORCE ROW LEVEL SECURITY`
(`tenant_id = current_setting('app.tenant_id')`), so that lookup is effectively `WHERE tenant_id
= victim AND tenant_id = caller`, which can never match — the code falls into `orElseGet()`,
builds a fresh usage row for the *victim's* id, and the subsequent `save()` (an INSERT) is
rejected by that same RLS policy. **Net effect pre-fix: a reliable uncaught
`DataIntegrityViolationException` → 500** — not a silent cross-tenant write (RLS held as a
backstop) and not quota-griefing (the counter is never actually incremented), but an unhandled
500 instead of a clean 403. With `requireOwn` now the first statement in the controller, this
path is never reached at all for a cross-tenant call — `ForbiddenException` is thrown before
`IncidentService.create()` is ever invoked, mapped to a clean 403 by `GlobalExceptionHandler`.

### The sweep — verification method, on record

**The first pass** used `grep -rl "..." --include=*Controller.java` — a **filename-pattern**
filter. That silently excluded `IncidentControllerV2.java`, since it doesn't end in exactly
`Controller.java` (it ends in `ControllerV2.java`). This is the exact failure mode being guarded
against below: a filter keyed on naming convention misses anything that doesn't follow it.

**The corrected pass, and how it was independently checked (not just repeated differently
worded):**
1. **Enumeration:** `grep -rl "@RestController\|@Controller\b" src/main/java --include=*.java`
   — a **content search** for the Spring annotation itself, filtered only by file *extension*
   (`.java`, matching every source file), never by file *name*. Result: **42 files matched**.
   One, `GlobalExceptionHandler.java`, is `@RestControllerAdvice` (matched only because that
   string contains the substring `@RestController`) — excluded by inspection, not a real
   controller. **41 real controllers, arrived at by content-grep across every `.java` file, not
   by any naming assumption.**
2. **Independent cross-check, a different annotation entirely:** `grep -rl "@RequestMapping"
   src/main/java --include=*.java` (Spring's base class/method-level mapping, present on most
   but not all controllers) — same extension-only filter. Result: 34 files. Diffed both
   directions against the 41-controller list:
   - Files with `@RequestMapping` but *absent* from the 41 — the direction that would mean the
     annotation-based enumeration missed a real controller — **zero**.
   - Files in the 41 without `@RequestMapping` — 8, all expected (controllers using only
     method-level `@GetMapping`/`@PostMapping` with no class-level base path:
     `StripeWebhookController`, `LaunchDarklyController`, the three Slack controllers,
     `PublicStatusPageController`, `StatusPageController`).

   Two different Spring annotations, two independent content-searches, zero discrepancy in the
   direction that would matter. This is what "verified" means here — not that a second pass
   using the same technique agreed with the first, but that a *different* signal (a different
   annotation Spring itself uses for routing) was checked against the same file set and found
   nothing extra. It cannot rule out a hypothetical functional/`RouterFunction`-style endpoint
   with no annotation at all — this codebase doesn't use that style anywhere observed, but that
   is the honest limit of what static grep-based verification can claim.

Cross-referenced every `tenantId` taken as a path variable, query param, or request-body field
across all 41 controllers against a `TenantContext.requireOwn(...)` call in the same method — by
exact count first, then by reading the full method body for every file where the pattern was
mixed (path/query *and* body-based tenantId in the same file: `CalendarConnectionController`,
`LogEventController`, `IncidentCommandController`, `ProjectController`) rather than trusting the
count alone. Full inventory (77 endpoints across 30 controllers that take a client-supplied
`tenantId`, all 77 now guarded; 12 controllers take no client-supplied `tenantId` at all, listed
with why) was reported in full in chat when asked — not duplicated here to keep this report
readable, available again on request.

**Gaps found: 2 total.**
1. `IncidentController.create()` — the finding itself, fixed above.
2. **`IncidentControllerV2.list()`** (`GET /api/v2/incidents?tenantId=...`) — **new, found only
   by this sweep.** Took `tenantId` as a `@RequestParam`, passed straight to
   `incidentService.findAll(tenantId, ...)`, zero `requireOwn` call. Same RLS-backstop
   reasoning as finding 5 likely applied (confirmed by the test below: 403, not a data leak) —
   fixed the same way, one line: `TenantContext.requireOwn(tenantId)` before the service call.

Everything else checked out clean. The inbound-webhook/Slack/Stripe controllers
(`StripeWebhookController`, `SlackCommandController`, `SlackInteractivityController`,
`SlackOAuthController`, `IntegrationsWebhookController`, `MonitoringWebhookController`) all
derive `tenantId` server-side (a resolved API key, a signed Slack workspace/state lookup, or
trusted webhook metadata) rather than accepting it as direct client input — a different shape,
correctly out of scope. `TotpController`, `AuthController`, `PushTokenController` take no
tenantId at all (scoped via `CurrentUserContext`/`TenantContext.get()`, or — for `AuthController`
— creating the tenant itself, nothing existing yet to own).

### Three incident controllers — noted for the record, not fixed

`IncidentController`, `IncidentCommandController`, and `IncidentControllerV2` now all exist.
Both gaps in this batch were a check present on one and missing on another of the same family.
Two follow-up questions checked, report-only, no code changed:

- **Does `IncidentControllerV2.getById` share `IncidentController`'s tenant-scoped query path,
  or does it have its own?** Checked directly: `IncidentControllerV2.getById` (line 30) calls
  `incidentService.findById(id)` — the exact same method `IncidentController.getById` (v1,
  line 67) calls. `IncidentService.findById` (`IncidentService.java:149-150`) queries via
  `incidentRepository.findByIdAndTenantId(id, TenantContext.get())` — scoped by the caller's own
  server-derived tenant, never a client-supplied value. **No separate query path, no gap here** —
  it inherited the Phase 1 fix for free by calling the same shared service method, not by
  independently getting it right.
- **Is `IncidentControllerV2` a live route or a half-finished migration?** One commit touches
  this file, ever (`9c593fb feat: add API versioning with /api/v2/incidents controller`, ~3
  months ago, no follow-up). 2 endpoints against v1's 12 — no create/update/delete, no
  evidence-pack/AI-analysis/runbook/postmortem endpoints. No other file in the repo references
  `/api/v2/incidents` — no backend caller, no docs, no config (this repo has no frontend to
  check). Everything visible points to an API-versioning effort started once and not continued,
  not a maintained parallel surface — can't rule out an external caller from what's visible here.

---

## Tests, per your spec (BY NAME, confirmed from surefire XML)

**`TenantIntegrationTest`** (7, rewritten — every existing test's `CreateTenantRequest`
constructor call updated to the new 2-arg shape; the old `createTenant_negativeBudget_returns422`
test was removed since its entire premise — a budget field with `@Min` validation — no longer
exists, replaced by the forged-JSON test below which tests the same underlying concern more
directly):
- `createTenant_noPlanOrBudgetSupplied_landsOnDefaultFreePlanWithZeroBudget` — **"a new tenant
  lands on the default plan"**, from your spec
- `createTenant_forgedPlanAndBudgetInRawJsonBody_ignored_stillLandsOnFree` — **"creating a
  tenant with a forged plan or budget is rejected"** — sends *raw JSON* (not a typed
  `CreateTenantRequest`, which literally can't express this anymore) with `plan`/
  `aiMonthlyBudgetCents` smuggled in alongside real fields; asserts the response still shows
  `FREE`/`0`, proving Jackson's silently-drop-unknown-properties behavior actually holds here,
  not just that the Java type system happens to prevent it
- `createTenant_nameTooShort_returns422WithFieldError`
- `createTenant_retentionDaysOutOfRange_returns422`
- `listTenants_returnsOnlyCallersOwnTenant`
- `getTenantById_existingTenant_returnsCorrectData`
- `getTenantById_notFound_returns404WithErrorShape`

**`IncidentTenantIsolationIntegrationTest`** (11, was 8 — added 3): existing 8 (`getById_*`,
`update_*`, `delete_*`, `resolveCommand_*` — GET/PATCH/DELETE/resolve, all already correctly
gated per Phase 1, unaffected by this change) plus:
- `create_forgedOtherTenantsTenantId_returns403_notServerError` — **"creating an incident with
  another tenant's tenantId returns 403, not 500"**, from your spec; also asserts
  `incidentRepository.findAll()` is empty afterward — nothing was created under either tenant
- `v2List_ownTenantId_returns200` — control/sanity for the sweep-found endpoint
- `v2List_forgedOtherTenantsTenantId_returns403_notServerError` — **the "one test per
  additional endpoint the sweep finds"** requirement, for `IncidentControllerV2.list()`

**`TenantOnboardingServiceTest`** (2, unchanged in count — both updated to the new
2-arg-fewer `onboard(...)` call): `onboard_createsTenantAndAdminUser`,
`onboard_callsServicesInCorrectOrder`.

**Also updated for compilation** (old 4-arg `CreateTenantRequest`/old `onboard(...)` calls,
mechanical fixes, no assertion logic changed except where a test's whole premise required a
specific plan — see below): `AetherOpsEndToEndFlowTest`, `RoleEnforcementTest`,
`WebhookSignatureIntegrationTest`, `TenantDataExportIntegrationTest`,
`TenantErasureIntegrationTest`, `ProjectApiKeyIntegrationTest`, `ServiceIntegrationTest`,
`OnboardingStatusIntegrationTest` — all previously passed a fixed `Plan.STARTER`/`Plan.TEAM`
that's now irrelevant (tenant always lands on FREE); dropped the now-nonexistent constructor
args, no assertions depended on the specific plan value in any of these.

**Three files needed a real fix, not just a mechanical one** —
`AuditLogIntegrationTest`, `RetentionIntegrationTest`, `UsageLimitIntegrationTest` — each has a
`createTenant(Plan plan)` helper whose entire purpose is standing up a tenant on a *specific*
plan to exercise plan-dependent behavior elsewhere in the same file (usage limits, retention
policy). The public API can no longer do that, so each helper now creates the tenant via the API
(lands on FREE) and then sets the plan directly via `tenantRepository.save(...)` — bypassing the
API for test setup, the same principle as the fix itself: a specific plan is trusted-path state,
not something a request should be able to declare.

Narrow run at implementation time —
`mvn test -Dtest=TenantIntegrationTest,IncidentTenantIsolationIntegrationTest,TenantOnboardingServiceTest,RoleEnforcementTest`
→ **Tests run: 25, Failures: 0, Errors: 0, Skipped: 0** (7 + 11 + 2 + 5).

No test was skipped, disabled, or weakened. `taskkill /F /IM java.exe` run before testing (no
orphaned processes found — clean); Docker Desktop confirmed running.

---

## Full test run

Docker Desktop confirmed running before the run; `taskkill /F /IM java.exe` run first (no
orphaned processes found).

```
mvn test
...
[INFO] Tests run: 999, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**999 tests, 0 failures, 0 errors, 0 skipped.** (996 going into this step + 3 new, all in
`IncidentTenantIsolationIntegrationTest` — `TenantIntegrationTest` stayed at 7 tests net,
`TenantOnboardingServiceTest` stayed at 2, matches exactly.)

No test was skipped, disabled, or weakened to get here.

---

## Files changed this step

Modified (Finding 7):
- `src/main/java/com/aetherops/tenant/model/CreateTenantRequest.java` — `plan`/
  `aiMonthlyBudgetCents` removed
- `src/main/java/com/aetherops/tenant/service/TenantService.java` — hardcodes `Plan.FREE`/`0`
- `src/main/java/com/aetherops/auth/service/AuthService.java` — updated caller, unused `Plan`
  import removed
- `src/main/java/com/aetherops/auth/service/GoogleAuthService.java` — same
- `src/main/java/com/aetherops/tenant/service/TenantOnboardingService.java` — `Plan plan`
  parameter removed from `onboard(...)`, unused import removed

Modified (Finding 5):
- `src/main/java/com/aetherops/incident/controller/IncidentController.java` — added
  `requireOwn` to `create()`
- `src/main/java/com/aetherops/incident/controller/IncidentControllerV2.java` — added
  `requireOwn` to `list()` (sweep finding)

Test files — rewritten:
- `src/test/java/com/aetherops/tenant/TenantIntegrationTest.java`
- `src/test/java/com/aetherops/incident/IncidentTenantIsolationIntegrationTest.java`

Test files — mechanical constructor-call fixes (+ `Plan` import cleanup where now unused):
- `src/test/java/com/aetherops/AetherOpsEndToEndFlowTest.java`
- `src/test/java/com/aetherops/auth/RoleEnforcementTest.java`
- `src/test/java/com/aetherops/integrations/webhook/WebhookSignatureIntegrationTest.java`
- `src/test/java/com/aetherops/privacy/TenantDataExportIntegrationTest.java`
- `src/test/java/com/aetherops/privacy/TenantErasureIntegrationTest.java`
- `src/test/java/com/aetherops/project/ProjectApiKeyIntegrationTest.java`
- `src/test/java/com/aetherops/service/ServiceIntegrationTest.java`
- `src/test/java/com/aetherops/tenant/OnboardingStatusIntegrationTest.java`
- `src/test/java/com/aetherops/tenant/TenantOnboardingServiceTest.java`

Test files — real fix (helper now sets plan via repository post-creation):
- `src/test/java/com/aetherops/audit/AuditLogIntegrationTest.java`
- `src/test/java/com/aetherops/retention/RetentionIntegrationTest.java`
- `src/test/java/com/aetherops/usage/UsageLimitIntegrationTest.java`

Fixed nothing outside findings 5 and 7 (the `IncidentControllerV2` fix is finding 5's own sweep,
not a separate scope expansion — you explicitly asked for it).
