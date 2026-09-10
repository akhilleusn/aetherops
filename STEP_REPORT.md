# Phase 2 Remediation — Step F4+F5: Trace ID & Cleanup (findings 1, 8, 9, 10, 13, 14, 16)

Status: **All seven findings IMPLEMENTED and tested.** Findings 9 and 14 were investigated and
reported first, per explicit instruction, then implemented after an explicit go-ahead on both.
Nothing committed.

---

## Findings 1 + 8 — `GatewayFilter` X-Trace-Id (one fix, both findings)

### The fix

`GatewayFilter` previously accepted `X-Trace-Id` verbatim whenever present and non-blank —
unauthenticated, unbounded, unvalidated — then wrote it into every log line for the request and
reflected it back in the response header. Now validated against a strict canonical-UUID regex
(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`); anything that
doesn't match — missing, blank, or malformed — is discarded and replaced with a freshly
generated `UUID.randomUUID()`, exactly the existing behavior for an absent header. A well-formed
client-supplied UUID is still honored, so distributed tracing across services still works.

Used a hand-written regex rather than `UUID.fromString(...)` in a try/catch deliberately: older
JDK versions have a known issue where certain malformed UUID strings throw exceptions other than
`IllegalArgumentException` (e.g. `ArrayIndexOutOfBoundsException`) from `UUID.fromString`, which
would need a second catch clause to stay safe — the regex sidesteps that class of bug entirely
and never throws.

### Tests

`GatewayFilterTest` (new, 5 tests): a malformed value (`<script>...`) is replaced with a fresh
UUID, not reflected; a non-UUID-shaped-but-plausible string is replaced; a genuinely valid UUID
is preserved byte-for-byte; a missing header still generates a fresh UUID (regression guard —
unchanged prior behavior); and a header carrying embedded newline/injected-header-looking content
is neither reflected into the response nor allowed to reach downstream log lines unmodified.

---

## Finding 16 — `EmailService` dev-mode fallback logging

### The fix

Previously: `apiKey.isBlank()` → `log.info("DEV MODE — ... link for {}: {}", to, link)`, i.e. the
live verification/reset token, at INFO, unconditionally, any time the API key happened to be
unset (including an accidental production misconfiguration).

Now:
- The fact that the fallback fired, and who it was for, is always logged **at WARN** (loud
  enough that a misconfigured production deployment is visible in normal log monitoring) —
  **never** the link or token.
- A new, separate opt-in property, `app.resend.expose-dev-links` (default `false`), gates
  whether the link/token is additionally logged. This decouples "I want to see the link
  locally" from "the API key happens to be unset" — the previous design conflated the two,
  meaning the only way to ever see a token in local dev was the same condition that silently
  leaks it in a misconfigured production deploy.
- A `@PostConstruct` method logs a loud WARN at application startup whenever
  `app.resend.expose-dev-links` is `true`, naming the property and stating it must never be
  enabled in production — so enabling it can't silently ride along into a deploy unnoticed.

### Tests

`EmailServiceTest` (new, 6 tests, using a Logback `ListAppender` attached directly to
`EmailService`'s logger — the only way to actually assert on log *content*, which is what this
finding is about): dev-mode fallback for both `sendVerificationEmail` and
`sendPasswordResetEmail` logs the recipient but never the link or the token substring; the
fallback log is at WARN, never INFO; explicitly enabling `expose-dev-links` does log the link;
the startup warning fires when the property is on and does NOT fire when it's off (default).

---

## Finding 13 — stale RLS-coverage comment, plus a full sweep

### `PrivacyController`'s comment

Claimed "RLS only covers 3 of the 38 tenant-scoped tables" — a snapshot from before this
session's V60 migration (Phase 1), now off by an order of magnitude. Corrected using the live
schema, not hand-counted from migration files (hand-counting is exactly how the original number
went stale — see below): **35 of the 40** tables carrying a `tenant_id` column have
`FORCE ROW LEVEL SECURITY` today. The five deliberate exceptions — `api_keys`, `invitations`,
`refresh_tokens`, `slack_workspaces`, `users` — are each already documented with their own
reasoning in their migrations (`V60`, `V62`). The corrected comment also now points to
`TenantScopedSchemaService.tenantScopedTables()` as the live, authoritative source, rather than
re-asserting a number that will itself go stale the next time a tenant-scoped table is added —
same fragility class, closed at the source this time rather than just patched.

**How the 35/40 figure was obtained:** *not* by re-grepping migration files by hand — that
method has now produced a wrong answer twice this session (the original audit's "3 of 38", and
my own first attempt at hand-counting `CREATE TABLE ... tenant_id` blocks in this same
investigation, which undercounted at 38 because two tables — `incidents` and `log_events` — got
their `tenant_id` column via a later `ALTER TABLE`, not inline in their original `CREATE TABLE`,
so a naive per-statement text scan misses them). Instead, queried the real, fully-migrated test
database directly: `TenantScopedSchemaService.tenantScopedTables()` for the 40 (same
`information_schema.columns` query GDPR export/erasure themselves rely on), and
`SELECT relname FROM pg_class WHERE relforcerowsecurity = true` for the 35. This is exactly the
kind of check a comment should defer to rather than duplicate.

### The sweep

Grepped every file in `src/main/java` and every migration in `src/main/resources/db/migration`
for `RLS`/`Row Level Security`/`row-level security` (14 files total) and read each match in
context. Beyond `PrivacyController`, found:

| File | Claim | Verified against | Accurate? |
|---|---|---|---|
| `PublicStatusPageController` | Public route reads RLS-protected tables, needs pre-auth tenant resolution | Traced call path to `StatusPageService.resolveTenantIdForSlug` | Yes |
| `StatusPageService` (×2 comments) | `status_pages` IS RLS-protected since V60; `resolveTenantIdBySlugBypassingRls` is the one sanctioned exception | V60 migration, V68 migration | Yes (already corrected earlier this session) |
| `PushNotificationService` | RLS hides another tenant's token registration, causing the collision this fix addresses | Traced to V67 fix already landed | Yes |
| `WebhookSigningSecretService` | Lookup must run inside `@Transactional` for `TenantContextInterceptor` to apply `app.tenant_id` | `TenantContextInterceptor.java` | Yes |
| `TenantContextInterceptor` | Propagates `app.tenant_id` via `set_config` | Read the method itself | Yes (self-referential, trivially accurate) |
| `ApiKeyAuthFilter` | Webhook/ingestion endpoints never hit `TenantContextFilter`, so `app.tenant_id` needs explicit propagation here | `TenantContextFilter` only reads `Authorization: Bearer`, confirmed | Yes |
| `TenantContext.requireOwn` javadoc | "RLS doesn't cover every table" (no specific count) | No number to go stale | Yes |
| `SlaMonitoringJob` / `SlaTrackingService` | `incidents` is RLS-scoped, referencing V28 | V28 migration | Yes |
| `V60__enable_rls_on_remaining_tenant_tables.sql` | Explains why `users`/`api_keys`/`refresh_tokens` are excluded | Cross-checked against `ApiKeyAuthFilter`/`TenantContextFilter`'s actual lookup-by-hash/email logic | Yes |
| `V62__create_invitations_table.sql` | `invitations` not RLS-protected, "same reasoning as users/api_keys/refresh_tokens" | Confirmed `invitations` is absent from both V28's and V60's table lists | Yes |

**Only `PrivacyController`'s "3 of the 38" was stale.** Everything else checked out — none of
the other nine files assert a specific coverage *count* that could silently drift the way a
literal "N of M" figure can; they describe mechanism (how RLS/`app.tenant_id` propagation works,
or why one specific named table is/isn't covered), which doesn't go stale the way a number does.

This is the third stale security-property comment found this session (`status_pages` "carries no
RLS policy" — fixed in the public-status-page work; `PrivacyController`'s "3 of 38" — fixed here).
Both of the first two were caught only because they were directly in the way of an unrelated bug
fix, not because anyone was looking for them — worth treating "trust but verify" as the default
for any comment asserting a security property, not just when a bug forces the question.

---

## Finding 10 — `AuditLogExportService.csvEscape`

### The fix

`csvEscape` previously only guarded against breaking the CSV's own structure (comma, double
quote, newline) — nothing prevented a value starting with `=`, `+`, `-` or `@` from being
interpreted as a formula by Excel/Sheets/LibreOffice when the exported file is opened
(CSV/formula injection). Added a leading-apostrophe prefix for any of those four characters,
applied **before** the existing comma/quote/newline check (order matters: the prefix itself
never contains a comma/quote/newline, so it doesn't change whether outer quoting is needed).
A leading `'` is the standard mitigation — every mentioned spreadsheet application treats it as
"force text," so the cell still displays the original value, it's just never evaluated.

Also applied `csvEscape` to `entry.getResourceId()`, which — per the finding — was previously
written with `nvl()` and no escaping at all. `resourceId` is typed `UUID` on the `AuditLog`
entity, so it needed a `.toString()` conversion at the call site to match `csvEscape`'s `String`
signature; a UUID's textual form can never itself start with `=+-@` (always a hex digit), which
is exactly why this finding correctly says "not exploitable today" — but the escaping function
was incomplete regardless of whether today's callers can trigger it, and `message` (free text)
absolutely can.

### Tests

`AuditLogExportServiceTest` (2 new tests, alongside the 4 pre-existing ones, all still passing):
an end-to-end CSV export with `message = "=cmd|' /C calc'!A1"` produces a row where that field is
prefixed with `'` (verified as it would actually flow through the whole service, not just the
escape function in isolation); and a direct test of `csvEscape` itself (via
`ReflectionTestUtils.invokeMethod`, since it's private) proving all four trigger characters
(`=`, `+`, `-`, `@`) get the same leading-apostrophe treatment — this is the exact function the
`resourceId` column also goes through, so it stands in for a scenario that can't be constructed
end-to-end given `resourceId`'s real `UUID` type.

---

## Finding 9 — IMPLEMENTED (investigated first, then an explicit go-ahead)

### What was asked

Does `RedisConfig`'s `GenericJackson2JsonRedisSerializer` (which activates Jackson polymorphic
typing — embeds a `@class` field per value so it can deserialize back to the right Java type)
need to stay, or can the `RedisTemplate<String, Object>` bean be replaced with a type-safe
`Jackson2JsonRedisSerializer<TenantResponse>`? Does anything else use that bean?

### Findings

**Fully contained — one consumer, one type.** Grepped every reference to `redisTemplate` in
`src/main/java` (8 files). Seven of the eight (`MonitoringAlertReplayGuard`,
`TwoFactorAttemptService`, `LoginAttemptService`, `SlidingWindowRateLimiterService`,
`RateLimiter`, `FeatureFlagService`, and `RedisConfig` itself) inject `StringRedisTemplate` — a
completely separate bean, backed by `StringRedisSerializer`, entirely unaffected by anything
done to the `RedisTemplate<String, Object>` bean.

**`TenantService` is the sole consumer of `RedisTemplate<String, Object>`**, and it caches
exactly one type through it: `TenantResponse` (`(TenantResponse) redisTemplate.opsForValue().get(cacheKey)`
on read, `redisTemplate.opsForValue().set(cacheKey, response, CACHE_TTL)` on write, where
`response` is always a `TenantResponse`). `TenantResponse` itself
(`@Data @Builder`, no explicit constructor annotations, no final fields) gets a Lombok-generated
public no-arg constructor plus getters/setters — standard Jackson bean binding, no builder
annotation Jackson would need special handling for — so it's a clean drop-in for
`Jackson2JsonRedisSerializer<TenantResponse>` with no `@class` metadata needed at all, closing
finding 9's actual concern (unnecessary polymorphic-typing activation).

**What the change would look like** (spring-data-redis 3.4.1, confirmed on the classpath —
`Jackson2JsonRedisSerializer<T>(ObjectMapper, Class<T>)` is available, not deprecated):

```java
@Bean
public RedisTemplate<String, TenantResponse> tenantCacheRedisTemplate(RedisConnectionFactory connectionFactory) {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    RedisTemplate<String, TenantResponse> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new Jackson2JsonRedisSerializer<>(mapper, TenantResponse.class));
    return template;
}
```

Plus renaming the bean method (`redisTemplate` → `tenantCacheRedisTemplate`, to stop implying
it's a general-purpose bean now that it's scoped) and re-typing `TenantService`'s injected field
from `RedisTemplate<String, Object>` to `RedisTemplate<String, TenantResponse>` — at which point
`findById`'s explicit `(TenantResponse) ...` cast is no longer needed either, since the
serializer only ever produces that one type.

### The fix

Implemented exactly as scoped above: `RedisConfig`'s bean is now `tenantCacheRedisTemplate`
(`RedisTemplate<String, TenantResponse>`), backed by `Jackson2JsonRedisSerializer<>(mapper,
TenantResponse.class)` instead of `GenericJackson2JsonRedisSerializer`. `TenantService`'s field
is re-typed to match, and the `findById`'s previous manual `(TenantResponse) ...` cast is gone —
the serializer now guarantees the type.

### Old-format cache entries — asked to report and handle if needed; handled

A value written under the old `GenericJackson2JsonRedisSerializer` format (with a `@class`
discriminator) does **not** deserialize under the new type-scoped serializer. Confirmed what
that actually does without a catch: `Jackson2JsonRedisSerializer.deserialize` wraps the failure
in Spring Data Redis's `SerializationException` (a `RuntimeException`), which would otherwise
propagate straight out of `opsForValue().get(...)` uncaught — turning an ordinary cache read
into a 500 on the first request for any tenant that had been cached before this deploy. That's
the "uncaught exception" case the go-ahead said not to allow.

Fixed by wrapping the cache read in `TenantService.getCached(...)`: any exception from the get
(not just `SerializationException` by name, so this is robust to whatever the serializer
actually throws) is caught, logged at WARN with the tenant id, and treated as a cache miss —
identical to a key that was never cached. Falls through to the DB read, which then overwrites
the stale key with the new format via the existing `set(...)` call on the way out. Self-healing
within one TTL window (30 minutes) with no ops action needed, and no request-facing failure at
any point during the transition — confirmed by test, not just reasoned about (see below).

### Tests

New `TenantServiceTest` (4 tests, none existed before): a cache hit returns the cached value
without touching the repository; a cache read that throws `SerializationException` (simulating a
stale old-format entry) falls through to the repository and returns the correct value instead of
propagating, AND re-populates the cache in the new format on the way out (both asserted); an
ordinary cache miss populates the cache after the DB read; a tenant absent from both cache and DB
throws `ResourceNotFoundException` as before (regression guard — unrelated to this fix, but the
class had zero prior test coverage so it's included).

---

## Finding 14 — IMPLEMENTED (investigated first, then an explicit go-ahead)

### What was asked

---

## Finding 14 — IMPLEMENTED (investigated first, then an explicit go-ahead)

### What was asked

`TenantErasureService.erase()` and `TenantDataExportService.export()` run raw JDBC with an
explicit `WHERE tenant_id = ?` (parameterized on the method's `tenantId` argument) against
tables that also carry `FORCE ROW LEVEL SECURITY`. Is it reachable for a caller to invoke either
for a tenant that isn't their own — which would mean the RLS predicate (scoped to the *caller's*
`app.tenant_id`) and the explicit `WHERE` clause target two different tenants, and the query
would return the intersection (i.e., nothing, since a row can't simultaneously have two
different `tenant_id` values) — silently under-deleting or under-exporting rather than
throwing or leaking?

### Findings — NOT reachable, given the current call path

Traced the full path, not assumed:

1. **`PrivacyController` is the only caller of either service.** Grepped `erasureService\.` and
   `exportService\.export\(` across all of `src/main/java` — every match is inside
   `PrivacyController.java` itself or the two service files' own definitions. No scheduled job,
   admin tool, or other controller calls either service.
2. **Both endpoints call `TenantContext.requireOwn(id)` before touching the service**
   (`PrivacyController.java` lines 37 and 50) — this throws `ForbiddenException` unless `id`
   (the path variable, which becomes the `tenantId` argument passed into both services) equals
   `TenantContext.get()`, the caller's own tenant per their JWT. So by the time either service
   method runs, `tenantId` is already guaranteed to equal the caller's own tenant — there is no
   path where they can differ.
3. **`app.tenant_id` and `TenantContext.get()` are the same value, not just conceptually
   related.** `TenantContextInterceptor` is an `@Aspect` firing `@Before` every
   `@Transactional` method (`erase()` is `@Transactional`, `export()` is
   `@Transactional(readOnly = true)` — the pointcut matches both), and it sets
   `app.tenant_id = TenantContext.get()` directly — the identical ThreadLocal value
   `requireOwn` just checked.

Given (1)+(2)+(3): every call to `erase(tenantId, ...)`/`export(tenantId)` reachable through the
one controller that calls them has `tenantId == TenantContext.get() == app.tenant_id` for that
entire request. The scenario the finding describes — explicit `WHERE tenant_id = ?` targeting
one tenant while RLS's `app.tenant_id` silently restricts to another — has no way to occur on
this call path today.

### The fix

Not a code-behavior change, since there's nothing broken — added the documenting comment at the
top of both `TenantErasureService.erase()` and `TenantDataExportService.export()`, exactly as
directed: stating plainly that each method's raw-JDBC `WHERE tenant_id = ?` is safe specifically
*because* its only caller (`PrivacyController`) already enforces `tenantId == TenantContext.get()`
via `requireOwn` before calling in, spelling out precisely what goes wrong (silent
under-deletion / under-export, not a throw or a leak) if that ever stops holding, and stating in
bold that a future second caller MUST preserve the same guarantee before calling in. No test
required or added — no behavior changed, only documentation.

---

## Tests — full list, this batch

- `GatewayFilterTest` (new, 5 tests) — findings 1 + 8
- `EmailServiceTest` (new, 6 tests) — finding 16
- `AuditLogExportServiceTest` (2 new tests added to the existing 4; all 6 pass) — finding 10
- `TenantServiceTest` (new, 4 tests — this class had zero prior coverage) — finding 9

Finding 13 (comment correction) and finding 14 (documentation-only) have no dedicated tests —
neither changed behavior.

Scoped run across everything touched or adjacent (`GatewayFilterTest`, `EmailServiceTest`,
`AuditLogExportServiceTest`, `RequestIdFilterTest`, `PasswordResetIntegrationTest`,
`EmailVerificationServiceTest`) — **0 failures, 0 errors.**

Full `mvn test` run — see chat for exact counts confirmed by name from the surefire XML.
