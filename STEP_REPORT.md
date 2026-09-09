# Step E — Webhooks and Push Tokens (findings 5 and 7)

Status: **E1 implemented and tested. E2 signature verification IMPLEMENTED for GitHub, AWS
SNS/cloudwatch, PagerDuty, Grafana (HMAC/certificate) AND Datadog, New Relic, Opsgenie, Rollbar,
Alertmanager (static-token). Splunk is a documented, accepted risk — no mechanism exists to
close it, by design, not oversight. Sentry, VictorOps, Sumo Logic remain deliberately
unimplemented (unconfirmed schemes). Existing-tenant migration (item 4) IMPLEMENTED: a
grace-period column plus an off-by-default `LegacyWebhookSecretBackfillRunner` that generates a
per-provider secret for every tenant across the five static-token providers, with a transition
window before enforcement begins. Generated secrets are written to an owner-only-permissions
file or, only with an explicit `--print-secrets` flag, to stdout — never logged.** Nothing
committed.

---

## E1. Push Token IDOR (finding 7) — IMPLEMENTED

### The vulnerability

`POST /api/v1/users/{userId}/push-token` took `userId` from the path and `tenantId` from the
request body. Neither was checked against the caller's own identity. Any authenticated tenant
member could overwrite any other member's push token by supplying that member's `userId` in the
path and `tenantId` in the body — silently un-paging them. In an incident-response product this
is an availability failure, not just a privacy one.

### Fix

Both identifiers are now derived exclusively from the authenticated principal
(`CurrentUserContext.get()` / `TenantContext.get()`) — the same ThreadLocal pattern already used
throughout the codebase, populated from the caller's own JWT. They are **not accepted as request
input at all**, not validated-and-rejected-if-mismatched. There is no field, path variable, or
JSON property anywhere in the request that a caller's identity claim could land in.

### Files changed and why

- **`src/main/java/com/aetherops/push/RegisterPushTokenRequest.java`** — removed the `tenantId`
  field entirely. The request now carries only `expoPushToken` (`@NotBlank`) and `platform`.
  Removing the field (rather than keeping it and ignoring it) means there's no slot left for a
  caller to be misled into thinking selects who the token is registered for.

- **`src/main/java/com/aetherops/push/PushTokenController.java`** — route changed from
  `/api/v1/users/{userId}/push-token` to `/api/v1/users/me/push-token`; the `@PathVariable UUID
  userId` is gone. The handler now reads `CurrentUserContext.get()` and `TenantContext.get()`
  directly and passes them to `PushNotificationService.registerToken(...)`, whose signature was
  not changed.

  **Reasoning on removing the path variable (per your "consider... report your reasoning"
  instruction):** I removed it rather than leaving it in the route as a decorative/ignored
  parameter. Once the path segment can never affect which row is written, keeping it displayed in
  the URL would misrepresent the API as letting the caller target an arbitrary user — which is
  worse than not having the segment, because it invites exactly the assumption that caused the
  original bug (that the path value means something). `/users/me/push-token` keeps the resource
  under the same `/users/` namespace as `CalendarConnectionController`'s existing
  `/users/{userId}/...` routes, while making the self-only-ness part of the URL itself instead of
  something a reader has to go verify inside the handler body.

- **`src/test/java/com/aetherops/push/PushTokenControllerTest.java`** — rewritten (Mockito unit
  test). Sets `CurrentUserContext`/`TenantContext` in `@BeforeEach`, clears in `@AfterEach`.

- **`src/test/java/com/aetherops/push/PushTokenIntegrationTest.java`** — new file (MockMvc,
  extends `AbstractIntegrationTest`).

- **`PushNotificationService.java`, `PushDeviceToken.java`, `CurrentUserContext.java`** — read
  only, not modified. `registerToken(userId, tenantId, expoPushToken, platform)`'s signature is
  unchanged, so this fix is entirely at the controller/request-DTO boundary.

### New tests, confirmed BY NAME from surefire XML

`target/surefire-reports/TEST-com.aetherops.push.PushTokenControllerTest.xml`:
- `register_delegatesToServiceWithAuthenticatedPrincipal`
- `register_ignoresWhicheverPrincipalIsCurrentlySet_neverAClientValue`

`target/surefire-reports/TEST-com.aetherops.push.PushTokenIntegrationTest.xml`:
- `register_persistsUnderTheCallersOwnIdentity`
- `register_smuggledUserIdAndTenantIdInBody_areSilentlyIgnored_notHonored` — sends a raw JSON body
  with extra, unknown `userId`/`tenantId` fields pointing at a "victim" identity; confirms Jackson
  silently drops the unknown properties and the persisted row belongs to the real caller.
- `register_oldUserIdPathVariable_noLongerExists_returns404` — confirms the old vulnerable route
  is gone, not merely reauthorized.

Narrow run at implementation time: `mvn test -Dtest=PushTokenControllerTest,PushTokenIntegrationTest,PushNotificationServiceTest`
→ **Tests run: 13, Failures: 0, Errors: 0, Skipped: 0** (8 + 2 + 3). Full-suite result is in the
"Full test run" section below.

### A separate bug found and NOT fixed (out of scope for E1)

While testing E1 I wrote a fourth integration test,
`register_existingTokenFromAnotherCaller_reassignsToNewCallersOwnIdentity_neverToAThirdParty`,
which registered the same `expoPushToken` from two different tenants in sequence. It failed with
`500` instead of `200`, and I removed it from the test file rather than leave a permanently-red
test or silently fix something outside E1's scope. Reporting it here instead, as instructed:

- `PushNotificationService.registerToken` upserts by doing
  `tokenRepository.findByExpoPushToken(expoPushToken)`, falling back to `.orElseGet(() -> new
  PushDeviceToken(...))` and then `save()`.
- `push_device_tokens` is RLS-protected (`FORCE ROW LEVEL SECURITY`, `tenant_isolation` policy,
  `V60__enable_rls_on_remaining_tenant_tables.sql`), so that `findByExpoPushToken` is scoped to
  `app.tenant_id` for whichever tenant is calling.
- If tenant B's user registers the same `expoPushToken` string that tenant A already has on
  file, RLS hides tenant A's existing row from tenant B's `SELECT` (correctly — that's the
  isolation working as intended). The code then falls into `orElseGet`, builds a **new** entity
  with no id, and `save()` attempts an **INSERT**. `expo_push_token` has a unique constraint
  across the whole table (not scoped per-tenant), so the insert throws a constraint violation
  that isn't caught anywhere, surfacing as an uncaught 500 instead of either a sane reassignment
  or a clean rejection.
- This is a genuine bug (a device that reinstalls the app under a different tenant, or any token
  collision, currently 500s instead of failing gracefully), but it is not the IDOR in finding 7 —
  it reproduces with a single honest caller acting entirely within their own identity, no
  cross-tenant impersonation involved. I left it alone per "fix nothing outside these items" and
  am flagging it here per "anything you were unsure about."

---

## E2. Webhook Signature Verification (finding 5) — RECON ONLY, per instruction

**No verification code has been written for this section.** This is findings from documentation
research only, provider by provider, exactly as requested. Where a source could not be confirmed
with confidence, I've said so rather than guessing.

Endpoints in scope: `IntegrationsWebhookController` (grafana, newRelic, splunk, sentry, rollbar,
sumoLogic, opsgenie, victorOps, pagerDuty, cloudWatch, gitHub) and
`MonitoringWebhookController` (datadog, prometheus/alertmanager). All 13 currently authenticate
via `X-Api-Key` only, resolved through `apiKeyService.resolve(...)`, with no provider-specific
signature or replay check anywhere.

(Note, out of the 14-provider list I also noticed `LaunchDarklyController` uses the identical
`X-Api-Key`-only pattern. It wasn't named in your list of 12/14, so I'm not reporting it in detail
below — flagging its existence only in case it should be in scope for a later pass.)

### GitHub

- **Mechanism:** `X-Hub-Signature-256` — HMAC-SHA256 of the **raw request body**, using the
  webhook secret, hex-encoded, header value prefixed `sha256=`. Legacy `X-Hub-Signature`
  (HMAC-SHA1) also sent for backward compatibility but should not be relied on.
- **Timestamp/replay header:** **None.** GitHub documents no timestamp or nonce mechanism for
  webhook deliveries; replay protection isn't offered at the protocol level (GitHub does send a
  unique `X-GitHub-Delivery` ID per delivery attempt, which could be used for application-level
  deduplication, but that's not a signed anti-replay control).
- **Mandatory/optional:** Optional — only present if a secret is configured on the GitHub webhook.
- **Correct verification:** Compute HMAC-SHA256 over the raw (unparsed) request body bytes with
  the shared secret, hex-encode, compare to the value after `sha256=` using a constant-time
  comparison. Must operate on the raw body, not a re-serialized JSON object (whitespace/key-order
  differences would break the hash).
- **Confidence:** High — directly confirmed against GitHub's own docs.

### AWS SNS (feeds the `cloudWatch` endpoint)

- **Mechanism:** X.509-certificate-based signing, not HMAC. Every SNS message (`Notification`,
  `SubscriptionConfirmation`, `UnsubscribeConfirmation`) includes a `Signature`, `SigningCertURL`,
  and `SignatureVersion` field in the JSON body itself (not an HTTP header). Verification requires:
  1. Validating `SigningCertURL`'s host is a genuine `sns.<region>.amazonaws.com` domain (reject
     anything else — this is the step that stops a forged cert URL from being trusted).
  2. Fetching the X.509 cert from that URL and extracting its public key.
  3. Building a "string to sign" from a fixed, message-type-dependent ordered set of fields
     (different field lists for `Notification` vs `SubscriptionConfirmation`/
     `UnsubscribeConfirmation`), each as `FieldName\nFieldValue\n`.
  4. Verifying the base64-decoded `Signature` against that string using the public key —
     SHA1 for `SignatureVersion` 1, SHA256 for `SignatureVersion` 2.
- **Timestamp/replay:** The signed string includes `Timestamp`, but AWS's own docs (per this
  fetch) don't describe an enforced replay window — an application that wants replay protection
  has to check message age and cache seen `MessageId`s itself.
- **Mandatory/optional:** Effectively mandatory for any real trust in the source — this is the
  only mechanism SNS offers; there is no separate HMAC-secret option.
- **Correct verification:** Implement (or use an AWS-provided/well-vetted library for) the exact
  procedure above — this is the most complex of the 13 and the easiest to get subtly wrong (wrong
  field order, wrong field set for the message type, not validating the cert URL's host). Note
  the existing `confirmSnsSubscription` code already validates the confirmation-callback URL's
  host — it does **not** validate message *signatures*, so `Notification` messages currently pass
  through with zero cryptographic verification.
- **Confidence:** High — confirmed against AWS's own current documentation.

### PagerDuty

- **Mechanism:** V3 webhook subscriptions send `X-PagerDuty-Signature`, formatted as one or more
  `v1=<hex-hmac>` entries. Multiple secrets/rotation appears to be supported (I could not get
  PagerDuty's dedicated "verifying signatures" doc page to load its content through the fetch
  tool — two attempts at that specific page came back empty — so I can't quote PagerDuty's own
  text on whether the header can carry multiple `v1=` values comma-separated for key rotation, or
  confirm from their primary source whether it's body-only or body+something-else that's hashed).
  Secondary sources (not PagerDuty's own docs) consistently describe it as HMAC-SHA256 of the raw
  request body using a per-webhook secret shown at subscription-creation time.
- **Timestamp/replay header:** Not established from a source I'd call authoritative in this pass.
- **Mandatory/optional:** Optional — only applies to v3 webhook subscriptions, and only when a
  signing secret exists for that subscription.
- **Correct verification:** Very likely HMAC-SHA256 of the raw body, compared against the
  `v1=`-prefixed hex digest in `X-PagerDuty-Signature`, constant-time compare — but **I'm flagging
  this one as needing a direct read of PagerDuty's own "verifying signatures" page before
  implementing**, since I was not able to load it and don't want to hand you a scheme sourced only
  from third parties for something you'll actually implement.
- **Confidence:** Medium — header name and general v1=/HMAC-SHA256 shape corroborated by multiple
  independent secondary sources, but not verified against PagerDuty's own primary doc text in this
  pass.

### Sentry

- **Mechanism:** `Sentry-Hook-Signature` header — HMAC-SHA256 over the **raw request body**, using
  the integration's Client Secret, hex-encoded. A `Sentry-Hook-Timestamp` header is also sent,
  and `Sentry-Hook-Resource` identifies the payload type (e.g. `event_alert`).
- **Critical caveat — mandatory/optional depends on integration type:** These headers are
  documented as belonging to Sentry's **Internal Integration** / integration-platform webhooks
  (Settings → Developer Settings → Custom Integrations → "Internal Integration"), not to Sentry's
  older/simpler issue-alert "Webhook" notification action. **I could not confirm from this pass
  which of the two `IntegrationsWebhookController.sentry(...)` is actually built to receive.** If
  it expects the legacy simple issue-alert webhook payload, there may be no `Sentry-Hook-*`
  headers sent at all regardless of any secret configured, and this would need a code-level check
  of the expected payload shape (and possibly a decision to switch to the Internal Integration
  type) before signature verification could be added at all.
- **Timestamp/replay header:** `Sentry-Hook-Timestamp` exists; I did not confirm whether Sentry's
  own docs specify it as part of the signed material (i.e. HMAC over `timestamp + body`) or purely
  informational — the code example I found signs the body alone.
- **Correct verification (if the Internal Integration payload is what's received):** HMAC-SHA256
  of the raw body with the Client Secret, hex-encoded, constant-time compare to
  `Sentry-Hook-Signature`.
- **Confidence:** Medium-high on the mechanism itself, **low** on whether it applies to this
  codebase's actual Sentry integration without a code/config check first.

### Grafana

- **Mechanism:** Configurable HMAC-SHA256, header name configurable (**default**
  `X-Grafana-Alerting-Signature`), computed either over the raw body alone, or over
  `timestamp + ":" + body` when an optional "Timestamp Header" setting is also configured (that
  same timestamp is then sent in the configured timestamp header, giving replay protection when
  enabled).
- **Timestamp/replay header:** Optional, off unless explicitly configured (see above).
- **Mandatory/optional:** **Optional and off by default.** Signing is a contact-point setting the
  Grafana operator (the sender) must explicitly enable (Secret + Header, optionally Timestamp
  Header) — this is a relatively new feature (added ~Grafana 12.0, May 2025). If whoever
  configured this codebase's Grafana alerting contact point didn't turn signing on, no signature
  will ever be sent regardless of what verification code exists here.
- **Correct verification:** HMAC-SHA256 with the shared secret over either the raw body or
  `timestamp + ":" + body` (must match whichever mode the sender is configured for) compared to
  the configured header, constant-time. Given it's configurable per-installation, verification
  code would need the header name, whether a timestamp header is in use, and the secret as
  configuration, not hardcoded assumptions.
- **Confidence:** High on the mechanism from Grafana's own docs; **the practical question of
  whether it's actually enabled for whatever Grafana instance(s) send to this endpoint is unknown
  to me** and would need to be confirmed operationally, not just in code.

### VictorOps (Splunk On-Call)

- **Mechanism:** Multiple secondary sources reference an `X-VictorOps-Signature` (also written
  `X-Victorops-Signature`) header tied to the webhook's auth/routing key, used specifically for
  VictorOps's **outgoing/custom webhook** feature (distinct from the plain "REST Endpoint
  Integration," whose docs I fetched and which describes no signing at all — just a routing key in
  the URL). I could not obtain VictorOps/Splunk On-Call's own primary documentation text
  describing the exact string-to-sign construction or algorithm in this pass — the "REST Endpoint
  Integration" doc I successfully fetched is the wrong feature (no signature), and I was not able
  to load the specific "Escalation Webhooks" / outgoing-webhook page with signing details.
- **Timestamp/replay header:** Not confirmed.
- **Mandatory/optional:** Appears optional/feature-specific (tied to which VictorOps
  integration type is configured — outgoing custom webhook vs. REST endpoint), but I can't state
  this with confidence.
- **Correct verification:** **I do not have a confirmed answer for this provider.** I am
  explicitly not reporting the HMAC-SHA1/"MAC-SHA1"-with-sorted-POST-variables construction I'd
  seen referenced in earlier, lower-confidence secondary sources, because I could not verify it
  against VictorOps's own docs in this pass and you were explicit that a wrong scheme here is
  worse than the current gap. **This one needs a primary-source doc page before any verification
  code is written**, and I'd also want to confirm which VictorOps integration type
  `IntegrationsWebhookController.victorOps(...)` is actually built to receive, since it changes
  the payload shape as well as the auth mechanism.

### Opsgenie

- **Mechanism:** **No signature mechanism documented.** Opsgenie's own outgoing-webhook docs
  describe only the JSON payload shape and the ability to attach arbitrary custom headers (e.g.
  for a static API-key-style secret carried in a header of your choosing) — no built-in HMAC or
  signing capability is mentioned anywhere in their webhook integration documentation.
- **Timestamp/replay header:** None documented.
- **Mandatory/optional:** Absent — the feature doesn't exist.
- **What correct verification would look like:** Nothing cryptographic to verify. The only
  hardening available is the custom-header approach Opsgenie already supports on the sending
  side — i.e., what this codebase already does with a static `X-Api-Key`. There's no stronger
  provider-side mechanism to move to for Opsgenie specifically.
- **Confidence:** High.

### Datadog

- **Mechanism:** **No signature mechanism documented.** Datadog's webhook integration docs
  describe HTTP Basic Auth (embedded in the URL) and OAuth 2.0 as the available authentication
  options for the outbound call, plus arbitrary custom headers — no HMAC/signing feature is
  mentioned.
- **Timestamp/replay header:** None documented.
- **Mandatory/optional:** Absent.
- **What correct verification would look like:** Same as Opsgenie — nothing cryptographic
  available from Datadog itself; the strongest available control is a shared secret carried via
  a custom header or the URL, which is functionally what the existing `X-Api-Key` scheme already
  is.
- **Confidence:** Medium-high — one early search result had claimed Datadog does shared-secret
  "signing," but the direct fetch against `docs.datadoghq.com/integrations/webhooks/` found no
  such mechanism, only Basic Auth/OAuth/custom headers. I'm going with the primary-source result
  and reporting the earlier claim as unconfirmed/likely wrong.

### Prometheus Alertmanager

- **Mechanism:** **No signature mechanism documented.** `webhook_config`'s only authentication
  surface is the generic `http_config` block: HTTP Basic Auth, `Authorization` header
  (bearer/custom), OAuth2 client-credentials, TLS client certificates, and custom headers. No
  HMAC/request-signing capability exists in Alertmanager's webhook receiver config.
- **Timestamp/replay header:** None.
- **Mandatory/optional:** Absent.
- **What correct verification would look like:** Alertmanager is self-hosted by the customer, not
  a third-party SaaS sender, so there's no provider-side signing to adopt. The available hardening
  is the same shared-secret-via-header/bearer-token/mTLS options `http_config` already exposes on
  the sending side — again, roughly equivalent in strength to today's `X-Api-Key`, though mTLS
  would be a meaningfully stronger option if that's viable for this deployment.
- **Confidence:** High.

### Splunk

- **Mechanism:** **No signature mechanism documented.** Splunk Enterprise's built-in webhook alert
  action POSTs a fixed JSON payload to a configured URL with no signing, no custom headers, and no
  authentication mechanism beyond what's baked into the target URL itself (per Splunk's own docs:
  "the webhook makes an HTTP POST request to the URL" — no auth options are described at all for
  the built-in action). Third-party Splunkbase apps (e.g. "Better Webhooks") can add signing, but
  that is not the built-in action this codebase's `splunk` endpoint would be receiving from unless
  a specific non-default app is confirmed to be in use.
- **Timestamp/replay header:** None.
- **Mandatory/optional:** Absent.
- **What correct verification would look like:** Nothing native to verify from the built-in
  Splunk webhook action. If stronger authentication is wanted, it would have to come from
  configuring Splunk to send a static shared secret as a header value if that Splunk version's
  webhook action supports custom headers (not confirmed in this pass), or a different Splunkbase
  app entirely.
- **Confidence:** High that the built-in action has no signing; not confirmed whether the
  built-in action supports custom headers at all in current Splunk versions.

### New Relic

- **Mechanism:** **No signature mechanism documented.** New Relic's webhook notification channel
  docs describe Basic Auth, Bearer Token, and custom (optionally "encrypted"/masked) headers as
  the available authentication options for the outbound call — no HMAC/signing capability.
- **Timestamp/replay header:** None.
- **Mandatory/optional:** Absent.
- **What correct verification would look like:** Same pattern as Opsgenie/Datadog — no
  cryptographic mechanism from New Relic; a shared secret via custom header or Bearer token is the
  available option, equivalent in strength to today's `X-Api-Key`.
- **Confidence:** High.

### Rollbar

- **Mechanism:** **No signature mechanism documented.** Rollbar's own webhook security guidance
  explicitly recommends embedding an auth token as a query parameter in the target URL (e.g.
  `.../poke?auth=abc123...`) and validating it server-side — i.e., Rollbar's own recommended
  practice is functionally identical to a static API key, not a cryptographic signature.
- **Timestamp/replay header:** None.
- **Mandatory/optional:** Absent.
- **What correct verification would look like:** Nothing stronger available from Rollbar; the
  existing `X-Api-Key` approach is already in line with what Rollbar itself recommends (Rollbar
  suggests a URL query param specifically instead of a header, but the security property is the
  same).
- **Confidence:** High.

### Sumo Logic

- **Mechanism:** **Could not confirm either way in this pass.** I fetched Sumo Logic's webhook
  connections doc page twice (once via a redirect) and got only a high-level description of the
  feature ("Our webhook connections send Sumo Logic alerts to third-party applications") with no
  detail on authentication/signing options at all — not enough to say confidently whether a
  signature mechanism exists or is absent. I'm explicitly not reporting "no mechanism documented"
  here as a confirmed finding the way I am for Opsgenie/Datadog/New Relic/Rollbar/Splunk/
  Alertmanager, because in those cases I saw the actual authentication section and it plainly
  didn't include signing; for Sumo Logic I did not get to the relevant section of the docs at all.
  **This one needs another look before being treated as "confirmed absent."**

### Summary table

| Provider | Mechanism | Mandatory/Optional | Confidence |
|---|---|---|---|
| GitHub | `X-Hub-Signature-256`, HMAC-SHA256, raw body | Optional (secret must be set) | High |
| AWS SNS (cloudWatch) | X.509 cert-based signing, fields in body | Effectively mandatory to trust | High |
| PagerDuty | `X-PagerDuty-Signature`, `v1=`+HMAC-SHA256 (likely) | Optional | Medium — primary doc unreachable |
| Sentry | `Sentry-Hook-Signature`, HMAC-SHA256, raw body | Only for Internal Integration type | Medium — unsure which integration type this codebase expects |
| Grafana | Configurable header, HMAC-SHA256, optional timestamp | Optional, **off by default** | High on mechanism; operational status unknown |
| VictorOps | `X-VictorOps-Signature` referenced, construction unconfirmed | Unconfirmed | **Low — needs primary source** |
| Opsgenie | None documented | Absent | High |
| Datadog | None documented | Absent | High |
| Prometheus Alertmanager | None documented | Absent | High |
| Splunk | None documented | Absent | High |
| New Relic | None documented | Absent | High |
| Rollbar | None documented | Absent | High |
| Sumo Logic | Not established | Unknown | **Low — inconclusive research** |

### Where I'm explicitly uncertain (repeating per your instruction not to guess)

1. **PagerDuty** — couldn't load PagerDuty's own "verifying signatures" doc page (two attempts,
   both returned empty content through the fetch tool). What I've reported is corroborated by
   multiple independent secondary sources but not confirmed against PagerDuty's primary text.
2. **Sentry** — mechanism is well-documented, but I don't know whether this codebase's Sentry
   integration is set up as an "Internal Integration" (which would send the signed headers) or the
   legacy issue-alert webhook (which likely wouldn't). This needs a check of what payload
   `IntegrationsWebhookController.sentry(...)` currently parses/expects, or of how the Sentry side
   is configured, before it's known whether signature verification is even possible without first
   changing the integration type.
3. **Grafana** — mechanism confirmed, but it's opt-in on the sending side and I have no visibility
   into whether it's turned on for whatever Grafana instance(s) point at this endpoint.
4. **VictorOps** — could not get primary-source confirmation of the signature construction at all.
   Do not want to guess given "wrong implementation silently drops alerts."
5. **Sumo Logic** — inconclusive; the docs I reached didn't cover authentication at all.

I did **not** write any verification code for any provider in this pass, per your instruction to
review findings first and tell me which to implement.

---

## E2 implementation — GitHub, AWS SNS, PagerDuty, Grafana

Per your decision: implement signature verification for these four only. Sentry, VictorOps,
Sumo Logic explicitly not implemented (unconfirmed schemes — a wrong implementation silently
drops alerts).

### Design

**Raw body vs. parsing — how I solved it.** GitHub/PagerDuty/Grafana sign the raw request
body; verification has to run before Jackson parses it. Spring's `ContentCachingRequestWrapper`
(what `SlackSignatureFilter` uses for Slack) only *tees* bytes into its cache as something else
reads them via `getInputStream()` — that works for Slack because Spring parses its
form-encoded body via `getParameterMap()`, which `ContentCachingRequestWrapper` specifically
special-cases to populate the cache without consuming the stream. That special case does not
apply to a JSON body read via `getInputStream()`: draining it in a filter to verify a signature
would leave the stream empty, and Jackson would parse nothing afterward, silently breaking every
signed request's payload. Instead I wrote `CachedBodyHttpServletRequest`
(`com.aetherops.integrations.webhook`) — reads the body once into a `byte[]` in its constructor,
and overrides `getInputStream()`/`getReader()` to hand back a fresh stream over that same array
every time they're called. The filter verifies against `getCachedBody()` directly; the
controller's `@RequestBody` parsing later gets an independent, fully-intact stream over the
identical bytes. This is the standard "cache once as byte[], serve N times" pattern used broadly
for webhook signature verification — deliberately not `ContentCachingRequestWrapper`, for the
reason above.

**Where verification runs.** A new `WebhookSignatureFilter` (`OncePerRequestFilter`, plain
`@Component` like `SlackSignatureFilter`/`ApiKeyAuthFilter` — Spring Boot auto-registers any
`Filter` bean globally regardless of which `SecurityFilterChain` is active, so no `SecurityConfig`
change was needed) is scoped to exactly `POST /api/v1/integrations/{github,pagerduty,grafana}`.
It independently resolves `X-Api-Key` (same as `ApiKeyAuthFilter` does) to learn the tenant, then
looks up that tenant's signing-secret configuration.

**Opt-in, per your rule.** No enabled `WebhookSigningSecret` row for (tenant, provider) → log a
WARN ("No {provider} signing secret configured for tenantId={} — falling back to API-key-only
auth") and let the request through unchanged — existing behavior for tenants who haven't
configured signing is untouched. A row DOES exist → a missing or invalid signature is rejected
with 401, full stop, before the request reaches the controller.

**Constant-time comparison** — `MessageDigest.isEqual`, same as `SlackRequestVerifier`, never
`String.equals`/`.equals()`.

**PagerDuty secret rotation** — `X-PagerDuty-Signature` is split on `,`, each candidate trimmed,
verified independently; accepted if ANY matches. Directly tested (see below): a header with a
stale first value and a matching second value is accepted.

**Grafana replay window** — when a tenant configures a `timestampHeaderName`, the signature is
computed over `timestamp + ":" + body` (matching Grafana's documented scheme) and the timestamp
is checked against a 300-second window — same constant (`MAX_CLOCK_SKEW_SECONDS = 300`) and same
"outside skew → reject" logic as `SlackRequestVerifier`, per your instruction to match it.
Grafana's header name is also configurable per-tenant (`headerName` on the secret row), since
Grafana lets the *sender* choose it — defaults to `X-Grafana-Alerting-Signature` if unset.

**AWS SNS / cloudwatch is handled differently, deliberately.** SNS's own signing scheme (see E2
recon) is certificate-based and operates over specific **parsed field values** (Message,
MessageId, Timestamp, TopicArn, Type, etc. — exactly the fields AWS's own docs list, in the exact
order they specify), not raw bytes — so there's no "verify before parsing" concern for this one;
parsing must happen first by definition of AWS's own algorithm. It's also not a per-tenant opt-in
secret: AWS signs every SNS message unconditionally, so `SnsSignatureVerifier.verify(...)` is
called unconditionally in the `cloudwatch` handler (both `Notification` and
`SubscriptionConfirmation` types) and a missing/invalid signature is always a 401 — no fallback.

The specific mistake you flagged — trusting an attacker-suppliable `SigningCertURL` — is closed
by `AwsSnsHostValidator.isValidSnsHost(...)`, which rejects anything that isn't a genuine
`sns.{region}.amazonaws.com`(`.cn`) host **before the URL is ever fetched**. This is the exact
same check the endpoint's pre-existing `SubscriptionConfirmation` confirm-flow already did
(previously a private method/regex on the controller) — I extracted it into this shared utility
so both AWS-URL checks in the codebase stay in lockstep rather than risking drift between two
copies of the same regex.

### Files changed/added

New (`com.aetherops.integrations.webhook`):
- `WebhookSigningProvider.java` — enum: GITHUB, PAGERDUTY, GRAFANA (deliberately not the other
  9 — see above).
- `WebhookSigningSecret.java` — entity, table `inbound_webhook_signing_secrets`.
- `WebhookSigningSecretRepository.java`
- `WebhookSigningSecretService.java` — `@Transactional` lookup/configure; reuses
  `TicketIntegrationTokenEncryptor` (AES/GCM, `app.integrations.token-encryption-key`) rather
  than adding a new encryptor/env-var, since this table lives in the same
  `com.aetherops.integrations` domain that encryptor already exists to protect secrets for.
- `CachedBodyHttpServletRequest.java` — see "Raw body vs. parsing" above.
- `WebhookSignatureFilter.java` — GitHub/PagerDuty/Grafana verification.
- `AwsSnsHostValidator.java` — shared SNS-host check.
- `SnsSignatureVerifier.java` — AWS SNS certificate-based verification.

New migration:
- `V65__create_inbound_webhook_signing_secrets.sql` — table + RLS (`ENABLE`/`FORCE ROW LEVEL
  SECURITY` + `tenant_isolation` policy, same pattern as V60). `header_name`/
  `timestamp_header_name` columns are Grafana-only (nullable, meaningless for GitHub/PagerDuty).
  AWS SNS deliberately has no row here — see design notes above.

Modified:
- `IntegrationsWebhookController.java` — injected `SnsSignatureVerifier`; `cloudwatch(...)` now
  verifies the SNS signature unconditionally before doing anything else; the old private
  `SNS_HOST_PATTERN`/`isValidSnsSubscribeUrl` were removed in favor of the shared
  `AwsSnsHostValidator`. **The github/pagerDuty/grafana handler methods themselves are
  UNCHANGED** — verification is entirely enforced by the filter before the controller runs.
- `CloudWatchSnsRequest.java` — added `messageId`, `topicArn`, `timestamp`, `subject`, `token`,
  `signature`, `signingCertURL`, `signatureVersion` fields (previously only `type`, `message`,
  `subscribeURL` existed — insufficient to build AWS's "string to sign" or verify anything).
- `IntegrationsWebhookControllerTest.java` — added a mocked `SnsSignatureVerifier` (existing
  CloudWatch tests default it to "valid" so they keep testing what they tested before — alarm
  mapping and subscription-confirmation logic, not signature verification); added one new test
  for the SNS-rejected path.

### Judgment calls made without stopping to ask (report per your standing rule)

1. **Where per-tenant signing secrets live and how they're set.** You didn't specify a storage
   design, so I added persistence (entity + repository + service, keyed by tenant + provider,
   matching `TenantTicketIntegration`'s existing precedent) but **did not add a public
   configuration API** (no controller to PUT/GET/DELETE a tenant's signing secret) — that felt
   like a separate, larger decision (who can set it, what the request/response shape is) beyond
   "implement verification." Right now the only way to populate a row is directly via
   `WebhookSigningSecretService.configure(...)` — which is what the tests do — or a direct DB
   write. **This means the feature is inert for every real tenant until a configuration path
   exists.** Flagging this explicitly: do you want a management endpoint next, or is
   ops/support-provisioned fine for now?
2. **Per-tenant, not per-project, secrets.** `ApiKey` is project-scoped but your own instruction
   said "a signing secret configured for that tenant's integration" — I followed that literally
   and scoped `WebhookSigningSecret` to `tenantId`, not `projectId`. If a tenant has multiple
   projects each receiving GitHub webhooks from different repos/orgs with different secrets,
   this design can't express that (one GitHub secret per tenant, not per project). Flagging in
   case that's wrong for how tenants actually use projects here.
3. **SNS verification is unconditional, not opt-in with API-key fallback**, unlike the other
   three. I made this call because AWS signs every message unconditionally (there's nothing to
   "opt into" — every real SNS message can be verified), and your own framing of SNS in the E2
   recon as "effectively mandatory to trust" pointed the same direction. Flagging in case you'd
   rather it also fall back to API-key-only when unsigned, for parity with the other three.

### Tests, per your spec (valid accepted / invalid → 401 / missing+configured → 401 / PagerDuty multi-sig)

New file `WebhookSignatureIntegrationTest` (MockMvc, real Postgres via Testcontainers, exercises
the actual filter chain — not a direct controller call, since the filter is what enforces this):

- `github_validSignature_accepted`
- `github_invalidSignature_rejected`
- `github_missingSignature_secretConfigured_rejected`
- `github_noSecretConfigured_fallsBackToApiKeyOnly_accepted`
- `pagerDuty_validSignature_accepted`
- `pagerDuty_invalidSignature_rejected`
- `pagerDuty_missingSignature_secretConfigured_rejected`
- `pagerDuty_multiSignatureHeader_onlySecondValueMatches_accepted` — the specific rotation case
  you called out: a stale first value + a matching second value is accepted.
- `grafana_validSignature_accepted`
- `grafana_invalidSignature_rejected`
- `grafana_missingSignature_secretConfigured_rejected`
- `grafana_customHeaderName_validSignature_accepted` — Grafana's configurable header name
- `grafana_timestampMode_validSignatureWithinWindow_accepted`
- `grafana_timestampMode_outsideReplayWindow_rejected` — the 300s skew check

New file `SnsSignatureVerifierTest` (unit test):
- `validSignature_accepted`
- `tamperedMessage_afterSigning_rejected`
- `signatureFromDifferentKeyPair_rejected`
- `nonAwsSigningCertUrl_rejected_withoutEverFetchingIt` — the SigningCertURL-spoofing check,
  and confirms the cert URL is never even fetched when the host check fails
- `httpNotHttpsSigningCertUrl_rejected_withoutEverFetchingIt`
- `missingSignature_rejected`
- `subscriptionConfirmation_validSignature_accepted`

This required generating a real self-signed X.509 certificate + RSA key pair to produce
genuinely verifiable signatures — there's no public `java.security` API to construct/sign a
certificate (only to parse one), so the test shells out to the JDK's own bundled `keytool` once
in `@BeforeAll` to generate a throwaway PKCS12 keystore, then does all the actual
signing/verification through plain `java.security.Signature`. No new test dependency added.

New file `AwsSnsHostValidatorTest` (unit test): genuine host accepted, China-partition host
accepted, lookalike host (`sns.us-east-1.amazonaws.com.evil.com`) rejected, http (not https)
rejected, arbitrary attacker host rejected, null/blank/malformed rejected.

Modified `IntegrationsWebhookControllerTest`: added
`cloudWatch_invalidSnsSignature_rejectedAndNeverIngestedOrConfirmed`.

Narrow run at implementation time —
`mvn test -Dtest=WebhookSignatureIntegrationTest,SnsSignatureVerifierTest,AwsSnsHostValidatorTest,IntegrationsWebhookControllerTest`
→ **Tests run: 58, Failures: 0, Errors: 0, Skipped: 0** (14 + 7 + 7 + 30). Full-suite result is
in the "Full test run" section below.

---

## E2 implementation — Datadog, New Relic, Opsgenie, Rollbar, Alertmanager (static-token), plus Splunk risk acceptance and replay guard

Approved with three changes to my original proposal, addressed in order below.

### 1. Static-token verification — implemented for all five

`WebhookSigningProvider` extended with `DATADOG, NEW_RELIC, OPSGENIE, ROLLBAR, ALERTMANAGER`
(alongside the existing `GITHUB, PAGERDUTY, GRAFANA`). No schema change was needed —
`WebhookSigningSecret`'s existing `encryptedSecret`/`headerName` columns are exactly what a
static token needs; the mode (HMAC vs static-token) is decided in code by which enum constant
it is (`WebhookSignatureFilter.HMAC_PROVIDERS`), not a stored column, since the mapping is fixed
per provider, never a tenant choice.

**Mechanism per provider**, matching what each one's own docs support for outbound custom auth
(per E2 recon):
- Datadog, New Relic, Opsgenie, Alertmanager: a header, default name `X-Webhook-Secret`,
  overridable per tenant via the same `headerName` field Grafana uses (these providers don't
  mandate a header name the way Grafana does — the tenant configures whatever their sending
  side lets them send — so one fixed, sensible default keeps this simple without losing the
  override escape hatch).
- Rollbar: a URL query parameter, `?auth=...`, per Rollbar's own documented convention (their
  guidance is explicitly a query token, not a header) — `WebhookSignatureFilter.verifyStaticToken`
  branches on `provider == ROLLBAR` to check `request.getParameter("auth")` instead of a header.

All five are plain constant-time string comparison (`MessageDigest.isEqual`) against the
decrypted secret — there's no signature to compute, only a shared value to match. Because
there's no raw body involved, these five do NOT go through `CachedBodyHttpServletRequest` at
all (`WebhookSignatureFilter.HMAC_PROVIDERS` gates that) — the body stream is never touched by
the filter for these, avoiding needless buffering.

**Cross-provider isolation — tested explicitly, as required.** Since each `WebhookSigningSecret`
row is looked up by `(tenantId, provider)`, a secret configured for Datadog is structurally
invisible to an Opsgenie lookup — there is no code path where Opsgenie's check could ever
consult Datadog's row. Tested directly: `datadogSecret_doesNotAuthenticateOnOpsgenieEndpoint_
evenWithBothConfigured` configures BOTH providers with *different* secrets for the same tenant,
then presents Datadog's secret to Opsgenie's endpoint (401) and Opsgenie's own secret to
Opsgenie's endpoint as a control (202). Also tested:
`datadogSecret_rejectedWhenUsedAsApiKeyOnSplunkEndpoint` — presents a Datadog static token as
the `X-Api-Key` itself against Splunk's endpoint; rejected 401, since it's not a valid API-key
hash. Splunk has no static-token mechanism to bypass in the first place (see #2 below) — this
test confirms a webhook-signing secret from elsewhere doesn't accidentally work as a substitute
credential anywhere, not even where there's nothing provider-specific to check.

### 2. Splunk — residual risk accepted, documented, not built around

Per your instruction: no Splunkbase app, no workaround. Splunk's built-in webhook alert action
supports no custom headers, signing, or auth of any kind beyond the target URL — there is
nothing to carry a second secret through, unlike the other five. Documented in two places:
- A code comment directly on `IntegrationsWebhookController.splunk(...)` (added), explicitly
  labeled "KNOWN, ACCEPTED GAP," explaining why (no mechanism exists) and what closing it would
  require (a third-party Splunkbase app, or dropping the built-in action), so a future reader
  sees a deliberate decision, not an oversight.
- Here, and in the E2 recon section above.

Splunk is excluded from `WebhookSigningProvider` entirely — there was never a design where it
could participate.

### 3. Replay protection — redesigned per your correction, then implemented

You're right that raw-body-hash was the wrong key — Datadog/Alertmanager legitimately resend a
byte-identical payload every time an unresolved alert re-evaluates, and hashing the body would
silently drop those. Redesigned to key on **(tenant, provider, alert fingerprint)**, reusing
`MonitoringAlertIngestionService`'s existing fingerprint (`sha256(tenantId:serviceId:alertName)`)
rather than inventing a second one — I exposed it as a new public
`computeFingerprint(tenantId, serviceId, alertName)` method (the existing private
`buildFingerprint(AlertSignal)` now just delegates to it), so the controller can compute the
identical fingerprint *before* `ingest()` is ever called, without needing a full `AlertSignal`
(severity/title/etc. aren't decided yet at that point).

**New `MonitoringAlertReplayGuard`** (Redis-backed, `SETNX`-with-TTL via
`StringRedisTemplate.opsForValue().setIfAbsent(key, "1", window)`), called from the controller
immediately before `ingestionService.ingest(...)` at all five static-token call sites
(Alertmanager's per-alert loop calls it once per alert, not once per request). A hit within the
window is logged at WARN with provider/tenant/fingerprint and the request is *not* ingested —
never silent — but still ack'd 202 to the sender, since a resend isn't the sender's fault to see
as an error.

**Window: 30 seconds by default** (`app.webhooks.replay-window-seconds`, test profile overrides
it to 1s so tests don't need a real 30s sleep). This is a judgment call, not a value you
specified — I picked it to sit well below any real provider's minimum re-fire interval (minutes,
at the fastest, from what I could find), so it only catches a near-immediate resend of a
captured request, not a legitimate periodic re-fire. If you know a provider that re-fires faster
than 30s, this needs to be shorter, or that provider's replay guard should be disabled entirely
rather than risk dropping real alerts.

**On honesty about value, per your note:** you're right that this is low-value — the existing
find-or-create-against-**open**-incidents path already absorbs the common case (a replay while
open just bumps `eventCount`, harmless), and the residual attack this closes is a replay
reopening/recreating an already-**resolved** incident, which is noise, not compromise. I
implemented it anyway because, once I actually designed it, it turned out **not** to be
invasive — it required no change to the shared `AlertSignal` record or its other 8 call sites,
just one new small component, one new public method, and one line at each of the 5 relevant
call sites. If you'd rather not carry this for the value it provides, it's easy to remove:
delete `MonitoringAlertReplayGuard`, the 5 call-site checks, and the `computeFingerprint`
exposure (`buildFingerprint` can revert to its original one-line form). I did not drop it
myself since it wasn't the invasive case your instruction anticipated, but I'm flagging that
the value-vs-code-added tradeoff is genuinely marginal, in case you'd still rather not carry it.

### 4. Migration for existing tenants — reconsidered per your follow-up, now implemented

My original judgment call above (not building a migration, because there's no table recording
which tenants use which provider) is still factually true, but you overrode it: you want existing
integrations to actually get a secret rather than staying permanently unauthenticated, with an
explicit distribution story and a transition window. Implemented as follows.

**The unresolved data problem, and how the design works around it rather than solving it.**
There is still no table anywhere recording "tenant X has a Datadog integration configured" —
that fact hasn't changed. Rather than trying to infer real per-provider usage (which would need
either a new usage-tracking table or mining webhook access logs, both out of scope here), the
migration generates a secret for **every tenant, for all five static-token providers**,
regardless of whether that tenant actually uses each one. This is the "waste" I flagged
originally — most (tenant, provider) pairs it creates will never be used — but it's cheap waste
(one row and one log line each, no ongoing cost) and it's the only way to guarantee no real,
currently-unauthenticated integration is missed without the missing usage data.

**New: a grace period, so a generated-but-not-yet-adopted secret never breaks anyone.**
`inbound_webhook_signing_secrets` gained a nullable `grace_period_ends_at` column
(`V66__add_webhook_signing_secret_grace_period.sql`). It's NULL (and enforcement is exactly as
strict as before) for every secret a tenant/operator configures directly via
`WebhookSigningSecretService.configure(...)` — only a migration-generated row ever sets it.
`WebhookSignatureFilter` now checks it before rejecting a failed check: if the row has a future
`gracePeriodEndsAt`, a missing/invalid static token is treated exactly like "no secret
configured" (logged at WARN, falls back to API-key-only auth) instead of a 401. Once that
verifies successfully — i.e. the tenant has actually added the generated value to their sending
side — it's accepted immediately, grace period or not. Once the grace period elapses, enforcement
becomes strict, same as any operator-configured secret.

**New: `LegacyWebhookSecretBackfillRunner`** (`ApplicationRunner`, off by default via
`app.webhooks.migration.backfill-legacy-secrets: false`) — meant to be flipped on for exactly one
deploy, then back off; it's idempotent regardless (`WebhookSigningSecretService.backfillIfAbsent`
never touches a (tenant, provider) row that already exists, so it will never clobber a secret an
operator already configured for real, and re-running it is harmless, just wasted work). For every
tenant × the same five static-token providers from item 1, it generates a 256-bit random secret
(`SecureRandom`, URL-safe base64) and inserts it with `gracePeriodEndsAt = now + grace-period-days`
(config, default 30 — `app.webhooks.migration.grace-period-days`).

Deliberately limited to the same five static-token providers as item 1, NOT GitHub/PagerDuty/
Grafana: those are real HMAC secrets the tenant already owns and configured on the sending side
(e.g. a secret typed into GitHub's own webhook settings) — a randomly generated value here would
never match what the sender actually signs with. A static token is different: it starts working
the moment the tenant copies the generated value into their sending side's custom header (or, for
Rollbar, the `auth` query param), which is exactly the mechanism the grace period gives them time
to do.

**How an operator distributes the generated secrets — and a mistake I made and then fixed.**
My first version of this runner logged each newly created secret as a single line
(`WEBHOOK_SECRET_BACKFILL tenantId=... provider=... secret=... graceEndsAt=...`). You caught this
before it went anywhere: that's live credential material flowing through the logger into
whatever log aggregation this deployment forwards to — retained indefinitely, readable by anyone
with log access. A credential leak, not a distribution mechanism, and I should not have reached
for it. Replaced with two explicit, mutually-exclusive outputs, neither of which ever goes
through the logger:

- `app.webhooks.migration.secrets-output-path` — if set, generated secrets are appended in
  plaintext to that file, which `LegacyWebhookSecretBackfillRunner.createOwnerOnlyFile` creates
  with owner-only permissions (`rw-------` via POSIX ACLs where the filesystem supports them —
  production targets a POSIX host — with a create-then-restrict fallback for a non-POSIX
  filesystem like a local Windows dev box). Only the secret **count** and the file **path** are
  logged; the log line never contains a value.
- `--print-secrets` (a CLI argument, not a config property, read via
  `ApplicationArguments.containsOption(...)`) — for a deployment where a file genuinely isn't an
  option. Secrets are written straight to `System.out`, never through the logger, and a WARN
  (with no secret values in it) records that this happened and how many. This is an explicit,
  per-invocation choice the operator opts into, not a default.

If neither is given, the runner now refuses to run at all — it doesn't query tenants, doesn't
generate anything, just logs an error naming both options. I chose "refuse" over "generate anyway
and drop the values" because a secret this runner can't hand back to the operator is
permanently undistributable the moment `run()` returns (it's stored encrypted, never held in
memory afterward) — silently generating one anyway would be strictly worse than not generating it.

There is still no self-service secrets UI or vault integration (same gap already flagged for the
four HMAC providers) — the file/stdout output above gets a value out of this runner, it isn't a
secure long-term home for it. The operator is expected to move each secret from the output file
to wherever they actually hand it to the tenant, then remove/rotate access to that file once
done. A proper fix (self-service rotation UI, tenant-visible integration status, secrets-vault
handoff) is real follow-up work, not something this migration can substitute for.

**Is the secret stored hashed or in plaintext? — confirmed: reversible AES/GCM encryption, not a
hash, and here's why.** `WebhookSigningSecretService.configure`/`backfillIfAbsent` both call
`TicketIntegrationTokenEncryptor.encrypt(...)` (the same AES/GCM encryptor Jira/ServiceNow tokens
already use) and store the result in `encryptedSecret`; verification
(`WebhookSignatureFilter`) calls `signingSecretService.decrypt(...)` to get the plaintext back.
This is genuinely necessary for the three HMAC providers (GitHub/PagerDuty/Grafana): verifying an
HMAC signature means recomputing it with the *exact original key* the sender used — a one-way
hash of that key cannot reproduce the same HMAC, so reversible encryption isn't a weaker choice
there, it's the only one that works at all.

For the five static-token providers, though, a one-way hash **was** an option — this codebase
already has the pattern (`ApiKey.keyHash`, SHA-256, compared via
`findByKeyHashAndActiveTrue` in `ApiKeyService`), and it's strictly stronger: a compromise of the
DB plus the AES key would let an attacker recover every static-token secret in plaintext, where a
hash-and-compare design wouldn't be reversible at all. I did not switch the static-token
providers to hashing — `WebhookSigningSecret` is one entity/table/service shared by both
HMAC and static-token providers, and splitting storage strategy by provider type would have been
a larger, uninstructed redesign of item 1's already-approved-and-tested design, not a fix to the
logging bug you actually flagged. Flagging this as a real, separate hardening opportunity (hash
the five static-token secrets like `ApiKey` does, keep encryption only for the three HMAC ones)
if you want it — happy to do it as its own change, just didn't fold it into this one uninvited.

**Why nobody's alerting breaks on deploy.** Unchanged from before, but now for a slightly
different reason: it's not that zero rows are created (the migration now creates many), it's that
every newly created row starts inside its grace period, during which a request that doesn't yet
present the new secret is treated identically to "no secret configured" — logged, not rejected.
The only way a real integration starts failing is if its grace period fully elapses without the
tenant ever adopting the secret, which is exactly the outcome the distribution step above exists
to prevent.

### Tests, per your spec

All added to the existing `WebhookSignatureIntegrationTest` (real filter chain via MockMvc):

- `datadog_validStaticToken_accepted`, `datadog_wrongStaticToken_rejected`,
  `datadog_missingStaticToken_secretConfigured_rejected`
- `newRelic_validStaticToken_accepted`, `newRelic_wrongStaticToken_rejected`
- `opsgenie_validStaticToken_accepted`, `opsgenie_missingStaticToken_secretConfigured_rejected`
- `rollbar_validStaticToken_inQueryParam_accepted`, `rollbar_wrongQueryToken_rejected`,
  `rollbar_missingQueryToken_secretConfigured_rejected`
- `alertmanager_validStaticToken_accepted`, `alertmanager_wrongStaticToken_rejected`
- `datadogSecret_doesNotAuthenticateOnOpsgenieEndpoint_evenWithBothConfigured` — the explicit
  cross-provider isolation test you required: both providers configured with *different*
  secrets, Datadog's secret presented to Opsgenie's endpoint is rejected, Opsgenie's own secret
  on its own endpoint is accepted as a control.
- `datadogSecret_rejectedWhenUsedAsApiKeyOnSplunkEndpoint` — a Datadog static token presented as
  the `X-Api-Key` itself against Splunk is rejected (not a valid key hash).
- `datadog_replayedFingerprintWithinWindow_rejectedAndNotIngested` — an immediate identical
  resend is 202-ack'd but verified via `IncidentRepository` to never have reached `ingest()`
  (`eventCount` stays 1, not 2).
- `datadog_legitimateRefireAfterWindow_accepted` — the same alert re-fired after the (test-
  shortened, 1s) window IS ingested (`eventCount` becomes 2), proving the guard doesn't
  permanently block a real re-fire.

Narrow run at implementation time —
`mvn test -Dtest=WebhookSignatureIntegrationTest,IntegrationsWebhookControllerTest,MonitoringWebhookControllerTest,SnsSignatureVerifierTest,AwsSnsHostValidatorTest`
→ **Tests run: 83, Failures: 0, Errors: 0, Skipped: 0** (30 + 30 + 9 + 7 + 7). Full-suite result
is below.

### 4's tests, per your spec

New, in `WebhookSignatureIntegrationTest`:
- `datadog_migrationBackfilledSecret_withinGracePeriod_missingToken_fallsBackToApiKeyOnly_accepted`
  — a migration-generated secret with a future grace period, no `X-Webhook-Secret` header sent at
  all: accepted, same as the pre-existing "no secret configured" fallback.
- `datadog_migrationBackfilledSecret_gracePeriodExpired_missingToken_rejected` — the same, but the
  grace period is already in the past: rejected, same as any operator-configured secret.
- `datadog_migrationBackfilledSecret_correctTokenPresented_acceptedRegardlessOfGracePeriod` — once
  the correct backfilled value IS presented, it's accepted immediately; the grace period only
  softens a failed check, never blocks a correct one.

New file `WebhookSigningSecretServiceTest` (Mockito unit test, covers
`backfillIfAbsent` — the one property this method must have, since it runs unattended from a
startup runner):
- `backfillIfAbsent_createsSecretWithGracePeriod_whenNoneExists`
- `backfillIfAbsent_doesNotOverwriteAnAlreadyConfiguredSecret` — an operator's real secret is
  never touched by a (re-)run of the migration.

Narrow run at implementation time —
`mvn test -Dtest=WebhookSignatureIntegrationTest,IntegrationsWebhookControllerTest,MonitoringWebhookControllerTest,SnsSignatureVerifierTest,AwsSnsHostValidatorTest,WebhookSigningSecretServiceTest`
→ **Tests run: 88, Failures: 0, Errors: 0, Skipped: 0** (30 + 33 + 9 + 7 + 7 + 2). Full-suite
result is below.

**Update after the logging fix:** `LegacyWebhookSecretBackfillRunner` now has a dedicated test —
once it had actual branching logic (refuse / write-file / print-stdout) worth covering directly,
rather than being a thin pass-through loop. New file `LegacyWebhookSecretBackfillRunnerTest`
(Mockito, constructs the runner directly with mocked `TenantRepository`/`WebhookSigningSecretService`,
`ReflectionTestUtils.setField(...)` for the `@Value`-injected config fields — same pattern as
`AlertSuppressionServiceTest` etc.):
- `run_refusesAndTouchesNothing_whenNeitherOutputPathNorPrintSecretsFlagGiven` — asserts
  `verifyNoInteractions` on both the tenant repository and the signing-secret service: the
  refusal happens before any tenant/secret work starts, not after generating and then discarding.
- `run_writesGeneratedSecretsToFile_withOwnerOnlyPermissions_whenOutputPathConfigured` — a real
  `@TempDir` file, asserts the tenant id and all five provider names land in its content, and
  (guarded by a POSIX-filesystem check, since this also runs on a Windows dev box) asserts the
  file's actual permissions are exactly `rw-------`.
- `run_doesNotOverwriteOrDuplicate_whenSecretAlreadyBackfilledForAllProviders` — every provider
  already has a real secret (`backfillIfAbsent` stubbed to return false); asserts no output file
  is created at all, since nothing was generated.
- `run_printsToStdoutOnly_andNeverThroughTheLogger_whenPrintSecretsFlagGiven` — captures
  `System.out` directly and asserts the secret value appears there under `--print-secrets`.

Narrow run at implementation time —
`mvn test -Dtest=LegacyWebhookSecretBackfillRunnerTest,WebhookSigningSecretServiceTest,WebhookSignatureIntegrationTest,IntegrationsWebhookControllerTest,MonitoringWebhookControllerTest,SnsSignatureVerifierTest,AwsSnsHostValidatorTest`
→ **Tests run: 92, Failures: 0, Errors: 0, Skipped: 0** (30 + 7 + 4 + 7 + 33 + 2 + 9). Full-suite
result is below.

---

## Full test run

Docker Desktop was confirmed running before each run. Five full-suite runs happened across this
step: after E1 (881), after E2's four-HMAC-provider implementation (910), after E2's
static-token/replay-guard implementation (926), after item 4's grace-period migration (931), and
after the credential-logging fix to `LegacyWebhookSecretBackfillRunner` (935, current/final).

```
mvn test
...
[INFO] Tests run: 935, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**935 tests, 0 failures, 0 errors, 0 skipped.** (881 E1 + 29 E2 HMAC/SNS + 16 E2 static-token/
replay-guard + 5 item-4 migration [3 grace-period + 2 `WebhookSigningSecretServiceTest`] + 4
`LegacyWebhookSecretBackfillRunnerTest` from the logging fix, matching exactly — see below.)

### E1 tests, confirmed BY NAME from surefire XML

`target/surefire-reports/TEST-com.aetherops.push.PushTokenControllerTest.xml`:
- `register_ignoresWhicheverPrincipalIsCurrentlySet_neverAClientValue`
- `register_delegatesToServiceWithAuthenticatedPrincipal`

`target/surefire-reports/TEST-com.aetherops.push.PushTokenIntegrationTest.xml`:
- `register_oldUserIdPathVariable_noLongerExists_returns404`
- `register_persistsUnderTheCallersOwnIdentity`
- `register_smuggledUserIdAndTenantIdInBody_areSilentlyIgnored_notHonored`

### E2 tests, confirmed BY NAME from surefire XML

`target/surefire-reports/TEST-com.aetherops.integrations.webhook.WebhookSignatureIntegrationTest.xml` (33):
- `github_validSignature_accepted`
- `github_invalidSignature_rejected`
- `github_missingSignature_secretConfigured_rejected`
- `github_noSecretConfigured_fallsBackToApiKeyOnly_accepted`
- `pagerDuty_validSignature_accepted`
- `pagerDuty_invalidSignature_rejected`
- `pagerDuty_missingSignature_secretConfigured_rejected`
- `pagerDuty_multiSignatureHeader_onlySecondValueMatches_accepted`
- `grafana_validSignature_accepted`
- `grafana_invalidSignature_rejected`
- `grafana_missingSignature_secretConfigured_rejected`
- `grafana_customHeaderName_validSignature_accepted`
- `grafana_timestampMode_validSignatureWithinWindow_accepted`
- `grafana_timestampMode_outsideReplayWindow_rejected`
- `datadog_validStaticToken_accepted`
- `datadog_wrongStaticToken_rejected`
- `datadog_missingStaticToken_secretConfigured_rejected`
- `newRelic_validStaticToken_accepted`
- `newRelic_wrongStaticToken_rejected`
- `opsgenie_validStaticToken_accepted`
- `opsgenie_missingStaticToken_secretConfigured_rejected`
- `rollbar_validStaticToken_inQueryParam_accepted`
- `rollbar_wrongQueryToken_rejected`
- `rollbar_missingQueryToken_secretConfigured_rejected`
- `alertmanager_validStaticToken_accepted`
- `alertmanager_wrongStaticToken_rejected`
- `datadogSecret_doesNotAuthenticateOnOpsgenieEndpoint_evenWithBothConfigured`
- `datadogSecret_rejectedWhenUsedAsApiKeyOnSplunkEndpoint`
- `datadog_replayedFingerprintWithinWindow_rejectedAndNotIngested`
- `datadog_legitimateRefireAfterWindow_accepted`
- `datadog_migrationBackfilledSecret_withinGracePeriod_missingToken_fallsBackToApiKeyOnly_accepted`
- `datadog_migrationBackfilledSecret_gracePeriodExpired_missingToken_rejected`
- `datadog_migrationBackfilledSecret_correctTokenPresented_acceptedRegardlessOfGracePeriod`

`target/surefire-reports/TEST-com.aetherops.integrations.webhook.WebhookSigningSecretServiceTest.xml` (2):
- `backfillIfAbsent_createsSecretWithGracePeriod_whenNoneExists`
- `backfillIfAbsent_doesNotOverwriteAnAlreadyConfiguredSecret`

`target/surefire-reports/TEST-com.aetherops.integrations.webhook.LegacyWebhookSecretBackfillRunnerTest.xml` (4):
- `run_refusesAndTouchesNothing_whenNeitherOutputPathNorPrintSecretsFlagGiven`
- `run_writesGeneratedSecretsToFile_withOwnerOnlyPermissions_whenOutputPathConfigured`
- `run_doesNotOverwriteOrDuplicate_whenSecretAlreadyBackfilledForAllProviders`
- `run_printsToStdoutOnly_andNeverThroughTheLogger_whenPrintSecretsFlagGiven`

`target/surefire-reports/TEST-com.aetherops.integrations.webhook.SnsSignatureVerifierTest.xml` (7):
- `validSignature_accepted`
- `tamperedMessage_afterSigning_rejected`
- `signatureFromDifferentKeyPair_rejected`
- `nonAwsSigningCertUrl_rejected_withoutEverFetchingIt`
- `httpNotHttpsSigningCertUrl_rejected_withoutEverFetchingIt`
- `missingSignature_rejected`
- `subscriptionConfirmation_validSignature_accepted`

`target/surefire-reports/TEST-com.aetherops.integrations.webhook.AwsSnsHostValidatorTest.xml` (7):
- `genuineSnsHost_valid`
- `genuineChinaPartitionSnsHost_valid`
- `lookalikeHost_invalid`
- `httpNotHttps_invalid`
- `attackerControlledArbitraryHost_invalid`
- `nullOrBlank_invalid`
- `malformedUrl_invalid`

`target/surefire-reports/TEST-com.aetherops.integrations.IntegrationsWebhookControllerTest.xml` (1 new):
- `cloudWatch_invalidSnsSignature_rejectedAndNeverIngestedOrConfirmed`

---

## Files changed this step (complete list)

E1:
- `src/main/java/com/aetherops/push/RegisterPushTokenRequest.java` — modified
- `src/main/java/com/aetherops/push/PushTokenController.java` — modified
- `src/test/java/com/aetherops/push/PushTokenControllerTest.java` — modified
- `src/test/java/com/aetherops/push/PushTokenIntegrationTest.java` — new

E2, pass 1 (GitHub/SNS/PagerDuty/Grafana — HMAC/certificate):
- `src/main/resources/db/migration/V65__create_inbound_webhook_signing_secrets.sql` — new
- `src/main/java/com/aetherops/integrations/webhook/WebhookSigningProvider.java` — new (extended further in pass 2, see below)
- `src/main/java/com/aetherops/integrations/webhook/WebhookSigningSecret.java` — new
- `src/main/java/com/aetherops/integrations/webhook/WebhookSigningSecretRepository.java` — new
- `src/main/java/com/aetherops/integrations/webhook/WebhookSigningSecretService.java` — new
- `src/main/java/com/aetherops/integrations/webhook/CachedBodyHttpServletRequest.java` — new
- `src/main/java/com/aetherops/integrations/webhook/WebhookSignatureFilter.java` — new (extended further in pass 2)
- `src/main/java/com/aetherops/integrations/webhook/AwsSnsHostValidator.java` — new
- `src/main/java/com/aetherops/integrations/webhook/SnsSignatureVerifier.java` — new
- `src/main/java/com/aetherops/integrations/controller/IntegrationsWebhookController.java` — modified (further modified in pass 2)
- `src/main/java/com/aetherops/integrations/model/CloudWatchSnsRequest.java` — modified
- `src/test/java/com/aetherops/integrations/webhook/WebhookSignatureIntegrationTest.java` — new (extended further in pass 2)
- `src/test/java/com/aetherops/integrations/webhook/SnsSignatureVerifierTest.java` — new
- `src/test/java/com/aetherops/integrations/webhook/AwsSnsHostValidatorTest.java` — new
- `src/test/java/com/aetherops/integrations/IntegrationsWebhookControllerTest.java` — modified (further modified in pass 2)

E2, pass 2 (Datadog/New Relic/Opsgenie/Rollbar/Alertmanager — static-token, Splunk risk
acceptance, replay guard). Sentry/VictorOps/Sumo Logic remain untouched (unconfirmed schemes):
- `src/main/java/com/aetherops/integrations/webhook/WebhookSigningProvider.java` — modified (5 new enum constants + javadoc)
- `src/main/java/com/aetherops/integrations/webhook/WebhookSignatureFilter.java` — modified (5 new protected paths, `verifyStaticToken`, HMAC-vs-static-token branching)
- `src/main/java/com/aetherops/integrations/controller/IntegrationsWebhookController.java` — modified (Splunk risk-acceptance comment; replay-guard calls in newRelic/opsgenie/rollbar; new `MonitoringAlertReplayGuard` constructor dependency)
- `src/main/java/com/aetherops/monitoring/controller/MonitoringWebhookController.java` — modified (replay-guard calls in datadog/prometheusAlertmanager; new constructor dependency)
- `src/main/java/com/aetherops/monitoring/service/MonitoringAlertIngestionService.java` — modified (new public `computeFingerprint(...)`; `buildFingerprint` now delegates to it)
- `src/main/java/com/aetherops/monitoring/service/MonitoringAlertReplayGuard.java` — new
- `src/test/java/com/aetherops/integrations/webhook/WebhookSignatureIntegrationTest.java` — modified (16 new tests: 5 providers × accept/reject, cross-provider isolation, Splunk-as-apikey, replay-window pair)
- `src/test/java/com/aetherops/integrations/IntegrationsWebhookControllerTest.java` — modified (new `MonitoringAlertReplayGuard` mock/constructor arg)
- `src/test/java/com/aetherops/monitoring/controller/MonitoringWebhookControllerTest.java` — modified (new `MonitoringAlertReplayGuard` mock)
- `src/test/java/com/aetherops/AbstractIntegrationTest.java` — modified (`app.webhooks.replay-window-seconds` test property, 1s instead of the 30s production default)

E2, pass 3 (item 4 — existing-tenant migration, grace period, backfill runner):
- `src/main/resources/db/migration/V66__add_webhook_signing_secret_grace_period.sql` — new
- `src/main/java/com/aetherops/integrations/webhook/WebhookSigningSecret.java` — modified (`gracePeriodEndsAt` field)
- `src/main/java/com/aetherops/integrations/webhook/WebhookSigningSecretService.java` — modified (new `backfillIfAbsent(...)`)
- `src/main/java/com/aetherops/integrations/webhook/WebhookSignatureFilter.java` — modified (grace-period fallback branch in the failed-verification path)
- `src/main/java/com/aetherops/integrations/webhook/LegacyWebhookSecretBackfillRunner.java` — new
  (rewritten once in-place after the logging fix below, still one file/one commit-worth of state)
- `src/main/resources/application.yml` — modified (`app.webhooks.replay-window-seconds` given an
  explicit entry; new `app.webhooks.migration.*` properties, including `secrets-output-path`)
- `src/test/java/com/aetherops/integrations/webhook/WebhookSignatureIntegrationTest.java` — modified (3 new grace-period tests + `backfillSecret` helper)
- `src/test/java/com/aetherops/integrations/webhook/WebhookSigningSecretServiceTest.java` — new
- `src/test/java/com/aetherops/integrations/webhook/LegacyWebhookSecretBackfillRunnerTest.java` — new (added with the logging fix below)

E2, pass 3 fix (you caught this before I called it done — secrets were being logged):
- `src/main/java/com/aetherops/integrations/webhook/LegacyWebhookSecretBackfillRunner.java` —
  modified: removed the `log.info(...)` line that included the plaintext secret value entirely;
  replaced with the refuse-unless-configured / owner-only-permissions-file / `--print-secrets`-
  to-stdout design described above. No other file needed to change for this fix.
