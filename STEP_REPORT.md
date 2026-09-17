# STEP H2 — Outbound Call Resilience (Phase 4 finding 2, HIGH)

Design approved as proposed, with three subtractions (no `@Retry` on Jira/ServiceNow, no
circuit breaker or retry on paging, no circuit breaker on webhook delivery) and two decisions
resolved by instruction (Jira/ServiceNow: bounded-wait synchronous, not true async; Twilio:
tighten explicitly). Implemented below. Not committed (per instruction — working tree only).

---

## Recon summary (full detail was reported and approved before implementation)

- **No outbound HTTP client in this codebase had a connect/read timeout**, except
  `SlackNotifier`'s own hardcoded 3s/5s. No Apache HttpClient5/OkHttp/Jetty client is on this
  project's classpath, so every Boot-autoconfigured `RestClient.Builder`/`RestTemplateBuilder`
  resolves to `JdkClientHttpRequestFactory` with no timeout set.
- **Anthropic's `resilience4j.circuitbreaker`/`bulkhead`/`retry` "anthropic-ai" instances** were
  the template: sliding-window 10, failure-rate 50%, 30s open-state wait, 3 half-open probes;
  bulkhead max-concurrent 10/100ms wait; retry 3 attempts exponential backoff, both ignoring
  `UsageLimitExceededException`/`ResourceNotFoundException`. Applied to a whole `@Transactional`
  business method, not just the HTTP call — a shape deliberately **not** copied for Jira/
  ServiceNow (see below).
- **The async executor** every `@Async` method shared was Spring Boot's default: core size 8,
  queue capacity effectively unbounded (`Integer.MAX_VALUE`). Since the queue never fills, the
  pool never grows past 8 and never rejects — a hang anywhere just grows one shared queue behind
  8 stuck threads, no isolation, no backpressure.
- **Sync-on-Tomcat-thread**: `TicketController` → Jira/ServiceNow (direct, unbounded).
  **Async-on-shared-pool**: webhook delivery, Slack notifications, paging (SMS+push).
  **Sync-on-the-single-default-`@Scheduled`-thread**: `EscalationCheckJob` → `EscalationService`
  → SMS→push→Slack — the same one thread every other scheduled job in the app also uses
  (`OutboxService`, `SlaMonitoringJob`, `WarRoomAutoOpenJob`), found to be the most severe single
  exposure.
- **Twilio already has a default timeout** — confirmed by decompiling `twilio-9.0.0.jar`
  (`com.twilio.http.HttpClient`'s static initializer): connect=10,000ms, socket(read)=30,500ms,
  via its own internal Apache HttpClient 4 (`NetworkHttpClient`), entirely independent of
  anything in this codebase. Bounded, but not short — tightened per instruction (see below).
  The genuinely unbounded risk in the paging path turned out to be upstream of Twilio: the PTO
  calendar pre-check (`PtoAwarenessService` → `CalendarOAuthService.refreshAccessToken` +
  `{Google,Outlook}CalendarBusyStatusClient`), which ran with no timeout at all, once per
  rotation participant, before Twilio was ever called.

## Design, as approved

1. **Connect/read timeouts on every outbound client, no exceptions.** `SsrfGuard.disableRedirects`
   used to replace the request factory wholesale, silently discarding any timeout a caller had
   already configured — fixed first (see "Bugs found" below), since Jira/ServiceNow/webhook
   delivery all route through it.
2. **Separate pools per integration.** Jira/ServiceNow get their own resilience4j
   `ThreadPoolBulkhead` each (own thread pool). Slack notifications, webhook delivery, and paging
   (SMS+push) get their own dedicated, bounded-queue Spring `@Async` executor each.
3. **Jira/ServiceNow moved off the request thread — bounded-wait, not true async (option a).**
   The actual HTTP call runs on its own bulkhead pool; the Tomcat thread still waits, but only up
   to a configured bound, and the response contract (`{ticketId: ...}` returned synchronously) is
   unchanged. True fire-and-forget async (option b) is a product decision — it changes what
   `TicketController`'s caller gets back — and is recorded as a follow-up, not built.
4. **Circuit breakers on Jira, ServiceNow, and calendar** (Google and Outlook as separate
   instances, so one provider's outage doesn't degrade checks against the other). Calendar's is
   safe specifically because `PtoAwarenessService` already fails open on any exception — tripping
   the breaker just makes a known-down provider fail (and fail open) faster.
   **No `@Retry` on Jira or ServiceNow** — both are non-idempotent POSTs (ticket creation); a
   retry after a timeout that actually succeeded server-side risks a duplicate ticket, and
   neither integration has an idempotency key to make that safe.
5. **Paging (Twilio, push) gets timeouts and its own isolated pool — no circuit breaker, no
   retry.** A circuit breaker's job is to stop attempting calls once they're predicted to fail;
   for paging, "stop attempting" is the same suppressed-page harm as the unauthorized-Slack-ack
   finding fixed in Step H1. It would also be inert as written: `OnCallService.pageOnCall`
   already catches every exception into a `PageResult` return value, not a thrown exception —
   resilience4j's circuit breaker/retry trip on thrown exceptions by default, so annotating it
   as-is would do nothing without restructuring, which isn't wanted anyway. No retry either:
   paging the same channel twice doesn't help and burns time off the fallback budget; the
   SMS→push→Slack sequence is the intended redundancy.
6. **Twilio's timeout tightened explicitly, not left inherited.** SDK default (10,000ms /
   30,500ms) confirmed by decompilation, then overridden via `Twilio.setRestClient(...)` with a
   custom `TwilioRestClient` built from a custom `RequestConfig`/`SocketConfig` — see
   `TwilioConfig`. Set to connect=3000ms/read=5000ms (`twilio.connect-timeout-ms`/
   `read-timeout-ms`, both env-overridable): Twilio's documented API latency is well under a
   second, so this leaves several times that as headroom while still failing inside ~8s worst
   case — short enough that `EscalationService`'s push/Slack fallback isn't meaningfully delayed
   and the small paging bulkhead pool isn't tied up.
7. **Every timeout and pool size is a named `application.yml` property**, not a Java constant —
   see the full list below. Each can be changed via env var with no rebuild.

---

## Files changed

**New:**
- `src/main/java/com/aetherops/config/RestClientTimeoutConfig.java` — `RestClientCustomizer`/
  `RestTemplateCustomizer` beans giving every default-built `RestClient.Builder`/
  `RestTemplateBuilder` in the app a connect/read timeout (Slack, calendar, push/Expo, the
  Anthropic health check, AWS SNS cert-fetch/subscription-confirm calls). Applied automatically
  by Spring Boot to every injection point, including the fresh-instance-per-call-site ones
  `SsrfGuard`'s own javadoc relies on.
- `src/main/java/com/aetherops/config/AsyncExecutorConfig.java` — `pagingExecutor`,
  `slackExecutor`, `webhookExecutor`: three small, named, bounded-queue `ThreadPoolTaskExecutor`
  beans (explicit `AbortPolicy`) replacing the one shared default pool.
- `src/main/java/com/aetherops/integrations/service/JiraApiClient.java` /
  `ServiceNowApiClient.java` — the actual HTTP call for each integration, in its own bean (Spring
  AOP proxies don't intercept same-class self-invocation, so `@CircuitBreaker`/`@Bulkhead` would
  silently no-op if left as methods on `JiraTicketService`/`ServiceNowTicketService` themselves —
  same reasoning this codebase already applies to `@Async`). Lets real call failures propagate as
  exceptions instead of swallowing them into `Optional.empty()` — a circuit breaker that never
  sees a failure can never trip.

**Changed:**
- `src/main/java/com/aetherops/common/security/SsrfGuard.java` — `disableRedirects` now takes
  `connectTimeout`/`readTimeout` and builds them into the same request factory it uses to disable
  redirects, instead of replacing a caller's already-configured factory with an unbounded one.
- `src/main/java/com/aetherops/integrations/service/JiraTicketService.java` /
  `ServiceNowTicketService.java` — now pure orchestration: tenant-config lookup (stays on the
  caller's thread), then a bounded wait on the new API client's future.
- `src/main/java/com/aetherops/webhook/WebhookService.java` — configured connect/read timeout
  into `SsrfGuard.disableRedirects`; `@Async` now targets `webhookExecutor`; TenantContext fix
  (see below).
- `src/main/java/com/aetherops/slack/SlackNotifier.java` — its existing hardcoded 3s/5s timeout
  is now `app.http-client.default-connect-timeout-ms`/`default-read-timeout-ms`; `@Async` now
  targets `slackExecutor`.
- `src/main/java/com/aetherops/slack/IncidentSlackNotificationService.java` — `@Async` now
  targets `slackExecutor`; TenantContext fix.
- `src/main/java/com/aetherops/oncall/IncidentPagingService.java` — `@Async` now targets
  `pagingExecutor`; TenantContext fix.
- `src/main/java/com/aetherops/escalation/EscalationService.java` — now `@Async("pagingExecutor")`
  instead of running synchronously on the shared `@Scheduled` thread; `@Transactional` dropped
  (see "Bugs found" below for why); TenantContext fix.
- `src/main/java/com/aetherops/calendar/OutlookCalendarBusyStatusClient.java` /
  `GoogleCalendarBusyStatusClient.java` — `@CircuitBreaker("calendar-outlook"/"calendar-google")`;
  stopped catching their own exceptions (see "Bugs found" below).
- `src/main/java/com/aetherops/integration/twilio/TwilioConfig.java` — builds and installs a
  custom `TwilioRestClient` with the tightened timeout after `Twilio.init(...)`.
- `src/main/java/com/aetherops/analysis/config/AIProviderConfig.java` — `anthropicAIProvider`
  now injects `RestClient.Builder` instead of calling `RestClient.create()` (see "Bugs found").
- `src/main/resources/application.yml` — see the full property list below.

**Test — changed/added:** `SsrfGuardTest`, `JiraTicketServiceTest`/new `JiraApiClientTest`,
`ServiceNowTicketServiceTest`/new `ServiceNowApiClientTest`, `WebhookServiceTest`,
`IncidentPagingServiceTest`, `EscalationServiceTest`, `OutlookCalendarBusyStatusClientTest`,
`GoogleCalendarBusyStatusClientTest`, `PtoAwarenessServiceTest`, new
`RestClientTimeoutConfigTest`, new `TicketBulkheadSaturationTest`. Details in "Tests added" below.

No production code outside the files above was touched.

---

## Bugs found while implementing (not part of the original finding)

1. **`SsrfGuard.disableRedirects` silently discarded any timeout already configured on the
   builder it was given**, because it replaced the request factory wholesale rather than
   composing with it. Exactly the three call sites that most needed a bounded timeout (Jira,
   ServiceNow, webhook delivery) were also the three routed through it. Fixed as part of this
   work (see above) — the fix this finding needed couldn't actually land without it.

2. **`TenantContext` (a plain `ThreadLocal`) was never propagated across any existing `@Async`
   boundary in this codebase**, independent of anything in this finding. `TenantContextInterceptor`
   reads `TenantContext.get()` on whatever thread is running a `@Transactional`-annotated call to
   set Postgres's `app.tenant_id` for RLS; every `@Async` method that touches an RLS-protected
   table (`WebhookService.deliver`, `IncidentSlackNotificationService.notify`,
   `IncidentPagingService.maybePage` — all three were already `@Async` before this change) ran on
   a *different* thread than whatever set `TenantContext`, so `app.tenant_id` was never set for
   those threads' DB connections. Per the RLS policies (`NULLIF(current_setting('app.tenant_id',
   true), '')::uuid`), an unset value makes every `tenant_id = ...` comparison `NULL`, which never
   matches — reads would silently return zero rows, writes would fail RLS's check. Traced through
   the code and the RLS policy SQL, **not observed against a live database** — Docker/
   Testcontainers unavailable in this session, same as every prior step. Fixed for all three
   existing call sites (each now sets/clears `TenantContext` explicitly as its own first/last
   action, since I was already touching each of these methods to change their executor) and for
   the newly-async `EscalationService.escalate`. This is a genuinely separate, significant bug —
   flagging it clearly rather than folding it silently into the executor-isolation work.

   `EscalationService.escalate` specifically dropped `@Transactional` rather than keeping it
   alongside the new `@Async`: Spring doesn't document a stable relative ordering between
   `@Async` and `@Transactional` advisors on the same method, so whether `TenantContext.set()` as
   the method's own first line would run before or after `@Transactional`'s advice fires is not
   something to rely on. Every DB write inside `escalate()` (`executionRepository.save`, and
   everything inside `onCallService`/`pushNotificationService`/`slackConversationService`) is
   already transactional on its own bean via Spring Data/its own annotations, invoked strictly
   after this method's `TenantContext.set()` — so correctness doesn't depend on `escalate()`
   having its own transaction boundary, only on that ordering, which dropping `@Transactional`
   here makes unambiguous instead of relying on unspecified proxy composition.

3. **`OutlookCalendarBusyStatusClient`/`GoogleCalendarBusyStatusClient` caught their own
   exceptions and returned `false`**, which would have made their new `@CircuitBreaker`
   completely inert — a circuit breaker that never sees a thrown exception can never trip. Fixed
   by letting the exception propagate; `PtoAwarenessService`'s own catch (already there, already
   tested) produces the identical fail-open outcome one level up, so end-to-end behavior is
   unchanged — added `PtoAwarenessServiceTest.isUserAvailable_busyStatusClientThrows_failsOpenReturnsTrue`
   to prove that directly. The same swallow-then-return-empty pattern existed in
   `JiraTicketService`/`ServiceNowTicketService` before this refactor, for the same reason —
   addressed by construction in the new `JiraApiClient`/`ServiceNowApiClient` split (they never
   had a chance to swallow anything, since that logic didn't exist yet when they were written).

4. **`AIProviderConfig.anthropicAIProvider` built its `RestClient` via `RestClient.create()`**,
   a bare static factory that bypasses Spring Boot's `RestClient.Builder` autoconfiguration (and
   therefore every `RestClientCustomizer`, including the new default timeout) entirely. The
   Anthropic API call this powers had no timeout at all, independent of the `anthropic-ai`
   circuit breaker/bulkhead/retry already wrapping it — those bound concurrency and retries, not
   the socket; a single hung call still occupied a bulkhead slot for as long as the OS allowed.
   Not named in this finding's scope, but directly the same bug pattern and a one-line fix
   (inject the builder, same as `anthropicHealthIndicator` right below it already did) — fixed
   opportunistically.

---

## Configuration added (all in `application.yml`, all env-overridable, none hardcoded in Java)

```
app.http-client.default-connect-timeout-ms        2000   (Slack/calendar/push/Anthropic-health/SNS)
app.http-client.default-read-timeout-ms            3000
app.integrations.jira.connect-timeout-ms           3000
app.integrations.jira.read-timeout-ms              5000
app.integrations.jira.call-timeout-ms              9000   (TicketController's bounded-wait backstop)
app.integrations.servicenow.connect-timeout-ms      3000
app.integrations.servicenow.read-timeout-ms         5000
app.integrations.servicenow.call-timeout-ms         9000
app.webhooks.delivery-connect-timeout-ms            3000
app.webhooks.delivery-read-timeout-ms               5000
app.async.paging.{core-pool-size,max-pool-size,queue-capacity}     4 / 4 / 50
app.async.slack.{core-pool-size,max-pool-size,queue-capacity}      4 / 8 / 100
app.async.webhook.{core-pool-size,max-pool-size,queue-capacity}    4 / 8 / 100
twilio.connect-timeout-ms                           3000   (SDK default: 10000)
twilio.read-timeout-ms                              5000   (SDK default: 30500)
resilience4j.circuitbreaker.instances.jira-ticket / servicenow-ticket / calendar-google / calendar-outlook
resilience4j.thread-pool-bulkhead.instances.jira-ticket / servicenow-ticket   (core/max 4, queue 10)
```

---

## Tests added

- **Hanging endpoint fails fast at the configured timeout, against a real local server (no
  mocks):**
  - `SsrfGuardTest.disableRedirects_honorsConfiguredReadTimeout_failsFastInsteadOfHangingForever`
    — covers Jira/ServiceNow/webhook delivery's shared mechanism directly (doesn't route through
    `JiraApiClient`'s public method for this, since `SsrfGuard.validate()` rejects loopback
    addresses unconditionally regardless of TLS — this isolates the timeout mechanism from that
    separate, already-covered check).
  - `RestClientTimeoutConfigTest.customizedRestClientBuilder_honorsConfiguredReadTimeout_failsFastInsteadOfHangingForever`
    — covers the Slack/calendar/push/Anthropic-health/SNS bucket's shared mechanism.
  - Twilio: not covered by an equivalent local-server test — its timeout lives inside the SDK's
    own internal Apache HttpClient wiring with no clean seam to redirect at a URL level without
    also faking Twilio's hardcoded API host. Covered instead by the decompiled-default evidence
    above and the paging-fallback tests below, which are the behavioral property that actually
    matters here.
- **Saturated bulkhead rejects rather than queueing unboundedly:**
  `TicketBulkheadSaturationTest.saturatedThreadPoolBulkhead_rejectsFurtherSubmissions_insteadOfQueueingUnboundedly`
  — exercises resilience4j's `ThreadPoolBulkhead` core API directly (core=1/max=1/queue=1: fill
  the thread, fill the queue, prove the third submission throws `BulkheadFullException`
  immediately). This is the same class the `jira-ticket`/`servicenow-ticket` annotations
  configure via `application.yml`; a full `@SpringBootTest` proving the named instances
  specifically needs `AbstractIntegrationTest`/Testcontainers, unavailable in this session (see
  Verification).
- **Paging path: slow Twilio does not block push/Slack fallback:**
  `IncidentPagingServiceTest.maybePage_slowFailingSmsAttempt_stillAttemptsPushFallback_doesNotBlockOnIt`
  and `EscalationServiceTest.escalate_slowFailingSmsAttempt_stillFallsBackToPush_doesNotBlockOnIt`
  — mock `OnCallService.pageOnCall` to sleep briefly then fail (simulating a call right up
  against its timeout bound) and assert push (and, transitively, Slack) still gets attempted and
  the whole call completes quickly, not blocked.
- **TenantContext-across-async fix:** no new dedicated test — this is a `ThreadLocal`/RLS
  interaction that only manifests against a real Postgres connection, which Docker/Testcontainers
  unavailability rules out verifying directly in this session (same limitation as everything
  else DB-related this session). The fix (explicit `set`/`clear` per method) is the same,
  already-established pattern used and left unverified-against-a-real-DB in Step H1's
  `SlackInteractivityController` fix, for the same reason.
- **Calendar circuit breaker validity:**
  `PtoAwarenessServiceTest.isUserAvailable_busyStatusClientThrows_failsOpenReturnsTrue` (new) plus
  updated `OutlookCalendarBusyStatusClientTest`/`GoogleCalendarBusyStatusClientTest` (their
  `_apiThrows_` tests now assert propagation instead of swallowing) together prove the exception
  now reaches where a circuit breaker can see it, and that the end-to-end fail-open guarantee
  still holds.

---

## Verification

- `mvn -o compile` / `mvn -o test-compile` — clean, no errors.
- Full suite: `mvn -o test` → **1083 tests run, 0 failures, 290 errors.** Every one of the 290
  traces to the identical `DOCKER_HOST tcp://localhost:2375 is not listening` →
  `NoClassDefFoundError: Could not initialize class com.aetherops.AbstractIntegrationTest` root
  cause (verified by grepping every individual error line, not assumed from the count) — the
  same pre-existing, local-Docker-unavailability baseline every prior `STEP_REPORT.md` this
  session has documented. **793 non-Testcontainers-gated tests pass, 0 failures, 0 unexpected
  errors** — nothing outside that baseline changed.
- Not verified in this session (needs Docker): `AIAnalysisServiceCircuitBreakerTest`-style
  `@SpringBootTest` verification that the new `jira-ticket`/`servicenow-ticket`/
  `calendar-google`/`calendar-outlook` resilience4j instances are wired correctly end-to-end
  through Spring's autoconfiguration (config-key spelling, bean names, etc.) — the
  `TicketBulkheadSaturationTest` and the `@CircuitBreaker` annotations' presence are verified;
  their live wiring through `application.yml` into the actual registered instances is not.
  Recommend a Docker-backed pass before this ships, specifically exercising a real `/tickets/jira`
  request against a stalled mock Jira server to confirm the full annotation stack (circuit
  breaker + threadpool bulkhead + `TicketController`'s bounded wait) behaves as designed
  end-to-end, not just at each unit boundary.

Nothing committed.

---

## Follow-ups (explicitly out of scope for this step — recorded, not built)

1. **True fire-and-forget async for Jira/ServiceNow ticket creation** (option b from the design
   discussion). Would remove the Tomcat thread's bounded wait entirely, but changes
   `TicketController`'s response contract — callers currently get `{ticketId: ...}` back
   synchronously; a fully async version would need a different shape (202 + poll, webhook-back,
   etc.). A product decision, not a resilience fix — the bounded-wait approach built here already
   closes the actual finding (a hanging Jira can no longer exhaust the Tomcat pool).
2. **Per-endpoint circuit breakers for webhook delivery.** Not built: a single shared circuit
   breaker across all tenants' webhook endpoints would be wrong (one tenant's dead endpoint would
   wrongly degrade delivery to every other tenant's healthy one), and resilience4j's
   annotation-driven config only supports statically-named instances, not one dynamically per
   endpoint. Possible via `CircuitBreakerRegistry`'s programmatic API, but that's real additional
   work, not a config tweak — deferred.
3. **The dead `RETRYING`/`nextRetryAt` webhook-delivery sweep — a real, separate bug, found while
   reading `WebhookService` for this step.** `attemptDelivery` records `attempts`, `nextRetryAt`,
   and a `RETRYING`/`FAILED` status on failure, but **nothing anywhere in this codebase ever reads
   `nextRetryAt` or re-attempts a `RETRYING` delivery** — no `@Scheduled` job, no queue consumer,
   nothing. A delivery that fails once is recorded as "will retry" and then, in fact, never does.
   Confirmed by grepping every reference to `WebhookDeliveryRepository`/`nextRetryAt` in the
   codebase — the only writer is `WebhookService` itself; there is no reader anywhere. Not fixed
   here — it's a missing feature (a redelivery sweep needs to be built), not a timeout/resilience
   gap, and belongs in its own batch.
