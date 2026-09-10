# Phase 2 Remediation — Systemic `DataIntegrityViolationException` handler + findings 3, 12, 15

Status: **All four pieces IMPLEMENTED and tested.** Nothing committed.

---

## 1. Systemic fix — `GlobalExceptionHandler` for `DataIntegrityViolationException`

### Why this first

This exact bug shape (an uncaught `DataIntegrityViolationException` reaching the client as a raw
500) has now recurred four times across this codebase's history: `push_device_tokens`
(cross-tenant collision), `status_pages.slug` (collision), the webhook DTOs (oversized fields),
and `MuteAlertRequest` (oversized fields). Per-site fixes only close the instances someone found.
The handler closes the whole class, including sites nobody has found yet.

### The fix

`GlobalExceptionHandler.handleDataIntegrityViolation(DataIntegrityViolationException, HttpServletRequest)`,
placed immediately before the `MethodArgumentNotValidException` handler:

- Extracts the root cause via `ex.getMostSpecificCause()` and checks its `SQLState`.
- `23505` (`unique_violation`) → **409 Conflict**.
- Anything else in `DataIntegrityViolationException`'s range — most commonly `23502`
  (`not_null_violation`) or `22001` (`string_data_right_truncation`) — → **400 Bad Request**.
- Logs at WARN with the request URI, resolved status, and root-cause message (never the full
  stack trace at WARN — this is routine bad input, not an operational error).

This distinguishes reliably: Postgres's own `SQLState` is authoritative and doesn't depend on
message-string parsing.

### Confirmed: does NOT swallow `SlugAlreadyTakenException`'s existing 409 path

Traced the actual call chain, not assumed:

- `SlugAlreadyTakenException` is a direct `RuntimeException` subtype (`extends RuntimeException`)
  — **not** a subtype of `DataIntegrityViolationException`. The two are siblings under
  `RuntimeException`.
- `StatusPageService.configure()` (the only place that throws `SlugAlreadyTakenException`)
  already catches the raw `DataIntegrityViolationException` itself, inside the service layer, and
  throws `SlugAlreadyTakenException` in its place. The original `DataIntegrityViolationException`
  instance never leaves that `catch` block.
- Consequence: the only exception type that can ever reach `@RestControllerAdvice` from that path
  is `SlugAlreadyTakenException`. `handleDataIntegrityViolation` never sees it, so there is no
  shadowing question to resolve by handler-declaration-order or `@ExceptionHandler`
  most-specific-match rules — the two handlers simply never compete for the same exception
  instance. This reasoning (not just the resulting behavior) is recorded as a javadoc block on
  the new handler in `GlobalExceptionHandler.java`, so a future reader doesn't have to re-derive
  it.
- `StatusPageIntegrationTest` (existing, from the earlier status-page-slug fix) already exercises
  this path end-to-end against a real DB and still passes unchanged — confirmed by running it in
  this batch's test pass, not assumed from memory.

### Status-code discrepancy, found and corrected during test-writing — reported here explicitly

The finding instructions for §2/§3/§4 below said "returns 400." Writing the integration tests
surfaced that this codebase's **existing**, pre-dates-this-session convention
(`GlobalExceptionHandler.handleValidation(MethodArgumentNotValidException)`, unchanged by this
batch) maps every `@Valid`/`@Size`/`@NotBlank` rejection to **422 Unprocessable Entity**, not 400
— confirmed by first writing tests that asserted 400, watching 13 of them fail with actual status
422, then reading the pre-existing handler to confirm this was deliberate/established rather than
a bug. `ForgotPasswordRequest`/`ResetPasswordRequest` — the exact pattern finding 15 says to
match — already return 422 today. Changing that global convention would be a large, out-of-scope,
high-blast-radius change touching every already-passing validation test in the app; it was not
part of any of findings 3/12/15's actual instructions, which were about adding validation, not
about the status code it produces. **Decision: tests assert the codebase's real, established
422 for all `@Valid`-triggered rejections below, and 400 only for the new
`DataIntegrityViolationException` handler** (which is this batch's own new code, and was
specified as 400 explicitly). Flagging this rather than silently reconciling it, since the
original instructions said 400 in all these cases.

---

## 2. Finding 3 — `@Size` validation on the 11 named webhook DTOs

Per-DTO decision, verified against migrations rather than assumed (`Incident.title VARCHAR(500)`,
`Incident.serviceId VARCHAR(100)`, `Incident.environment VARCHAR(50)` — all confirmed in
`V1__create_initial_schema.sql`; `LaunchDarklyFlagEvent.flagKey VARCHAR(255)`,
`.changedBy VARCHAR(255)` — confirmed in `V58__create_launchdarkly_flag_events.sql`):

**Reject via `@Size` + `@Valid`** (identifier-role fields — an oversized identifier is a bad
request, not something to silently mangle):

| DTO | Field(s) bounded | Limit | Maps to |
|---|---|---|---|
| `NewRelicWebhookRequest` | `entitiesData.entities[].name` | 100 | `Incident.serviceId` |
| `SentryWebhookRequest` | `data.issue.project` | 100 | `Incident.serviceId` |
| `RollbarWebhookRequest` | `data.item.project.name` | 100 | `Incident.serviceId` |
| `OpsgenieWebhookRequest` | `alert.source` | 100 | `Incident.serviceId` |
| `VictorOpsWebhookRequest` | `entity_id`, `entity_display_name` | 100 each | `Incident.serviceId` (either can be selected) |
| `PagerDutyWebhookRequest` | `event.data.service.summary` | 100 | `Incident.serviceId` |
| `GitHubDeploymentStatusRequest` | `repository.full_name` | 100 | `Incident.serviceId` |
| `GitHubDeploymentStatusRequest` | `deployment.environment` | 50 | `Incident.environment` |
| `LaunchDarklyWebhookRequest` | `name` | 255 | `LaunchDarklyFlagEvent.flagKey` |
| `LaunchDarklyWebhookRequest` | `member.email` | 255 | `LaunchDarklyFlagEvent.changedBy` |

**Truncate in the controller instead** (title-role fields — per the explicit instruction
"silently dropping an alert is worse than truncating a title," applied to every field whose sole
destination is `Incident.title` VARCHAR(500)):

`NewRelicWebhookRequest.title`, `SentryWebhookRequest…issue.title`,
`RollbarWebhookRequest…item.title`, `OpsgenieWebhookRequest…alert.message`,
`VictorOpsWebhookRequest.state_message`, `PagerDutyWebhookRequest…event.data.title` — all
truncated to 500 chars via a shared `IntegrationsWebhookController.truncate(String, int)` helper,
applied at the point of extraction, immediately before use. `GitHubDeploymentStatusRequest`'s
title is built entirely from already-`@Size`-bounded fields plus fixed literal text
(`"Deployment failed: " + repo + " (" + environment + ")"`), so it cannot exceed 500 and needs no
separate truncation — verified by arithmetic (worst case ≈173 chars), not just assumed.

**Truncate, not reject, for map-derived fields — structural exception, reported as a deliberate
deviation:** `GrafanaWebhookRequest` and `AlertmanagerWebhookRequest`'s `labels`/`annotations` are
`Map<String,String>`. Jakarta Bean Validation has no mechanism to constrain map *values* —
`@Size` on a `Map` field constrains the map's entry count, not the length of what's inside it.
Both `serviceId` (from `labels`) and `environment` (from `labels`) are truncated in the
controller (`IntegrationsWebhookController.grafana()` and
`MonitoringWebhookController.prometheusAlertmanager()`, which handles the Prometheus-native
Alertmanager endpoint using the identical extraction shape) rather than rejected, since rejection
isn't reachable at the annotation layer for these two providers. This is the one place finding
3's "reject vs. truncate" choice was made by structural necessity rather than by the
title/identifier distinction — noted here rather than silently applying a different rule with no
explanation.

`CloudWatchSnsRequest`/`CloudWatchAlarmMessage` — the SNS envelope carries no identifier/title
fields itself (the actual alarm payload is a JSON-encoded string inside `Message`, parsed
manually via a raw `ObjectMapper.readValue(...)` call in
`IntegrationsWebhookController.parseAlarmMessage`, not through Spring's binding, so `@Valid`
cannot reach it either way). `CloudWatchAlarmMessage.alarmName` is used as **both**
`Incident.serviceId` (100) and `Incident.title` (500) simultaneously — truncated once to the
tighter, 100-char limit, which trivially also satisfies the 500-char one, rather than truncating
twice to two different lengths for the same underlying value.

`@Valid` was added at every one of the 11 listed controller methods
(`IntegrationsWebhookController.grafana/newRelic/sentry/rollbar/opsgenie/victorOps/pagerDuty/
gitHub/cloudWatch`, `MonitoringWebhookController.prometheusAlertmanager`,
`LaunchDarklyController.webhook`) — including `cloudWatch`, even though `CloudWatchSnsRequest`
carries no annotations today, both per the literal instruction and so a future annotation there
takes effect without anyone having to remember to add `@Valid` separately.

---

## 3. Finding 12 — `MuteAlertRequest` / `AlertSuppressionController`

`@Size` added matching `alert_suppression_rules`' actual columns, verified against
`V52__create_alert_suppression_rules.sql` (all four nullable, so `@Size` alone — no `@NotBlank`
— is correct, since `@Size` treats `null` as valid and only constrains length when present):
`serviceId` 100, `alertName` 255, `reason` 500, `createdBy` 255. `@Valid` added at
`AlertSuppressionController.mute()`.

**Report (not fixed, as instructed): should `createdBy` derive from `CurrentUserContext`
instead?** Checked the exact analogous case, `AddCostEntryRequest.recordedBy` — it is **also**
still client-supplied plaintext today (`IncidentCostService` writes
`request.getRecordedBy()` straight through), i.e. this is an existing, still-open pattern in the
codebase, not something already resolved elsewhere that `MuteAlertRequest` diverged from.
`CurrentUserContext.get()` (the ThreadLocal populated by `CurrentUserContextFilter` from the JWT)
exposes only a `UUID` — the authenticated user's id — not an email or display name. So "derive
from `CurrentUserContext`" is not a drop-in replacement: it would change `createdBy`'s semantics
from an arbitrary free-text attribution string to a resolved user id (or would require an
additional lookup to turn that id into a display name), a real design decision affecting the
response shape client code may already parse. Recommend doing this — the current
field lets any caller attribute a mute to anyone, including impersonating another team member in
an audit trail — but implementing it wasn't in scope for this batch and touches
`AddCostEntryRequest` too if done consistently, so it's reported here rather than fixed.

---

## 4. Finding 15 — `AuthController` typed DTOs

Three new records added to `com.aetherops.auth.model`, matching `ForgotPasswordRequest`/
`ResetPasswordRequest`'s existing pattern exactly:

- `ResendVerificationRequest(@NotBlank @Email String email)`
- `RefreshTokenRequest(@NotBlank String refreshToken)`
- `LogoutRequest(@NotBlank String refreshToken)`

`AuthController.resendVerification/refresh/logout` now bind these instead of
`Map<String,String>`, with `@Valid` added at all three. Before this fix, a missing key (e.g. no
`refreshToken` in the body) meant `map.get("refreshToken")` returned `null`, which then reached
`RefreshTokenService.rotate`/`revokeRefreshToken` → `sha256(null)` → an uncaught NPE → 500.
Confirmed by tracing `RefreshTokenService.java` directly, not assumed. Now rejected at the
binding layer, before the service is ever called. Existing tests
(`RefreshTokenRotationIntegrationTest`) that POST `{"refreshToken": "..."}` JSON bodies continue
to pass unchanged — Jackson binds records by component name exactly as it bound the old
`Map<String,String>` by key, so no wire-format change.

---

## Tests

New file: `WebhookInputValidationIntegrationTest.java` (15 tests, real DB via `AbstractIntegrationTest`,
MockMvc against actual HTTP endpoints — not direct controller calls, so `@Valid` genuinely
executes):

- 9 oversized-field-rejected tests (New Relic, Sentry, Rollbar, Opsgenie, VictorOps, PagerDuty ×1,
  GitHub ×2 — `full_name` and `environment` separately, LaunchDarkly) — all assert **422** (see
  §1's status-code note).
- 1 truncation-not-rejection test (Grafana oversized `service` label) — asserts **202**, proving
  the map-derived path neither 500s nor incorrectly rejects.
- 1 `MuteAlertRequest` oversized `serviceId` test — asserts **422**.
- 3 `AuthController` missing-required-field tests (`resendVerification`, `refresh`, `logout`) —
  assert **422**, not 500. `refresh`'s covers the explicitly-required "refresh call with a
  missing refreshToken" case.
- 1 systemic-handler proof: LaunchDarkly webhook with `name` omitted entirely.
  `LaunchDarklyWebhookRequest.name` carries only `@Size` (finding 3's scope), not `@NotBlank`, so
  a missing value sails past Bean Validation untouched, reaches
  `LaunchDarklyFlagEventService.record()`, and hits `flag_key VARCHAR(255) NOT NULL` — this
  request is never touched by `@Valid` at all. Asserts **400** (not 422) — this is the one test
  in the file going through the new `DataIntegrityViolationException` handler, not
  `MethodArgumentNotValidException`, and its status is genuinely different for that reason.

Full suite of touched areas run together (166 tests: `WebhookInputValidationIntegrationTest`,
`AlertSuppressionControllerTest`, `AlertSuppressionServiceTest`, `LaunchDarklyControllerTest`,
`LaunchDarklyFlagEventServiceTest`, `IntegrationsWebhookControllerTest`,
`MonitoringWebhookControllerTest`, `MonitoringAlertIngestionServiceTest`,
`RefreshTokenRotationIntegrationTest`, `WebhookSignatureIntegrationTest`, `AuthIntegrationTest`,
`PasswordResetIntegrationTest`, `SecurityEnforcementTest`, `RoleEnforcementTest`) — **0
failures, 0 errors.**

Full `mvn test` run — see chat for exact counts confirmed by name from the surefire XML.
