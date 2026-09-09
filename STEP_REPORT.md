# Phase 1 Remediation — Step C: Auth Hardening

Closes audit findings 6, 8, 9, and the rest of 10. No code was committed — all changes are in the working tree.

## C0 — Recon

**Redis wiring.** `RedisConfig` (`com.aetherops.ratelimit`) provides both a `StringRedisTemplate` and a JSON `RedisTemplate<String,Object>` bean, backed by Spring Boot's auto-configured `RedisConnectionFactory` (Lettuce, from `spring.data.redis.*`). A separate `RedissonConfig` provides an independent `RedissonClient` bean (used elsewhere, e.g. `IdempotentIncidentService`). `SlidingWindowRateLimiterService` already uses `StringRedisTemplate` (a Redis ZSET sliding window) for `IpRateLimitFilter`. `StringRedisTemplate` was the natural, already-proven choice for C3's `LoginAttemptService` and C2's new `TwoFactorAttemptService`.

**LoginAttemptService (before this step).** In-memory `ConcurrentHashMap<String, AttemptRecord>` keyed by email. `MAX_ATTEMPTS = 5` (hardcoded constant). Lock duration configurable via `app.login.lock-duration-minutes` (default 15). No expiry for sub-threshold counts — they lingered until a success cleared them. Not shared across replicas: N pods gave N× the allowed attempts before a global lock would apply.

**Refresh tokens (before this step).** Stored **raw** (`UUID.randomUUID().toString()`, no hashing) in `refresh_tokens.token` (unique). `validateRefreshToken(token)` looked the row up directly by that raw value, checked revoked/expiry, and returned it — with **no rotation of any kind**: the same token stayed valid and reusable indefinitely until its 7-day expiry or an explicit revoke.

**Important finding, flagged rather than silently acted on:** `RefreshTokenService.createRefreshToken` (now `issue`) had **zero callers anywhere in `src/main/java`** before this step. `AuthResponse` had no `refreshToken` field, and neither `AuthService.buildResponse` (login), `TotpService.verifyAndIssueToken` (2FA success), nor `GoogleAuthService.authenticate` ever created one. The `/api/v1/auth/refresh` and `/logout` endpoints existed and referenced the service, but nothing could ever hand a client a token to present to them. This is a real, separate gap — wiring up issuance at login is a bigger, unstated decision (which flows get one, what UX changes, and Step B already found the frontend has no refresh-on-401 logic at all) — so it was **not** fixed here. `AuthResponse` now *can* carry a `refreshToken` (needed for `/refresh`'s own rotated-token response regardless), and the new tests seed a token directly via `RefreshTokenService.issue(...)`, the same way the endpoint would receive one from any future caller. Recommend a separate task to decide and wire this up.

## C1 — X-Forwarded-For

**The bug:** `IpRateLimitFilter.extractClientIp()` and `RateLimitResponseFilter.resolveKey()` both did `request.getHeader("X-Forwarded-For").split(",")[0].trim()` — the leftmost entry, which is always attacker-suppliable (whether there's a proxy in front or not), so anyone could reset their own rate-limit bucket by sending a different fake value on every request.

**Formula correctness check — flagged before implementing, not silently substituted.** The originally-specified formula `chain[chain.length - 1 - trustedProxyCount]` picks the *attacker-controlled* entry in the single-hop default case: with `trustedProxyCount=1` and a spoofed request producing `X-Forwarded-For: 6.6.6.6, 1.1.1.1` (where nginx/Railway appended the real client `1.1.1.1`), that formula gives index `2-1-1=0` → `6.6.6.6`, defeating the fix. I raised this with a worked example rather than guessing or silently changing it; you confirmed the corrected formula `chain[chain.length - trustedProxyCount]` (standard "trust proxy N" semantics), which resolves to `1.1.1.1` correctly in that same example.

**What was built:**
- `com.aetherops.security.ClientIpResolver` — the single shared component, injected into both filters. `index = chain.length - trustedProxyCount`; bounds-checked (`index < 0 || index >= chain.length` → falls back to `request.getRemoteAddr()`, covering both "chain shorter than trusted count" and the degenerate `trustedProxyCount=0` case).
- `app.security.trusted-proxy-count` added explicitly to `application.yml`, default `1` (Railway — the live deployment target per your Part-1 answer — puts exactly one hop in front of the app; the Kubernetes Ingress path is also one hop).
- Both filters now call `clientIpResolver.resolve(request)` instead of their own duplicated (buggy) parsing.

## C2 — Rate limit `/api/v1/auth/2fa/verify`

- Added `/api/v1/auth/2fa/verify` to `IpRateLimitFilter.isProtectedPath` (the existing IP-level window now also covers this path).
- **New `TwoFactorAttemptService`** (Redis-backed, same fail-closed pattern as C3): keyed by `sha256(partialToken)`, TTL 5 minutes (matching the partial token's own lifetime in `JwtService.generatePartialToken`). After 5 failed code/backup-code attempts against one partial token, `TotpService.verifyAndIssueToken` rejects immediately with a new `TwoFaSessionLockedException` (423) — **even if the correct code is subsequently presented** — before ever touching the code or the account's TOTP secret. This locks the session, not the account: a fresh login mints a fresh partial token and thus a fresh counter, matching your "do NOT lock the account" instruction.
- **Checked `/api/v1/auth/refresh` and `/api/v1/auth/reset-password` for the same gap — concluded no fix needed, and said so rather than silently skipping.** Both validate a `UUID.randomUUID().toString()`-derived secret (refresh: raw value pre-C4, now hashed; reset: SHA-256-hashed from the start) — full 122-bit entropy, not a 6-digit/short code. Brute-forcing either is computationally infeasible in a way a 1,000,000-possibility TOTP code is not. I did not add them to `isProtectedPath`.

## C3 — Login lockout to Redis

Rewrote `LoginAttemptService` to use the existing `StringRedisTemplate` instead of `ConcurrentHashMap`. The failure count and the lock are now the same Redis key with one TTL (`lockDurationMinutes`, default 15), refreshed on every failure — so a sub-threshold count now also expires automatically after that long of inactivity (the old in-memory version never expired one; this is a deliberate, required behavior change per "keys expiring automatically").

**Fails CLOSED, explicitly, as instructed:** `isLocked(email)` wraps its Redis read in try/catch — **if Redis is unreachable, it returns `true` (locked/deny)** rather than silently disabling brute-force protection for the outage's duration. `recordFailure`/`recordSuccess` also catch and log (best-effort; they don't change the gate, which is `isLocked`). `minutesUntilUnlock` falls back to the full configured lock duration if Redis is down (used only in the locked-response message).

## C4 — Refresh token rotation + reuse detection

- **Migration `V64__refresh_token_rotation.sql`**: `token` → `token_hash` (rename; only a SHA-256 hash is ever persisted now), added `family_id` (groups every token descended from one original login) and `used` (boolean). Safe with no data-migration step, per the C0 finding above — the table is empty in any real deployment reachable through the app's own code.
- **`RefreshTokenService` rewritten**: `issue(userId, tenantId)` mints a brand-new family; `rotate(rawToken)` validates by hash, and on success marks the presented token `used=true` and issues a new one in the *same* family (7-day expiry kept, per instruction — each rotation gets its own fresh 7-day window from its own issuance time, same duration as before, just now per-rotation). `revokeRefreshToken`/`revokeAllUserTokens` kept, adjusted for hashing.
- **Reuse detection**: presenting a token already marked `used=true` throws a new `RefreshTokenReuseException` (401) and revokes every token in that family — proven family-wide, not just the one token, by the new test asserting the sibling token from the *legitimate* first rotation is also dead afterward.
- **Bug found and fixed during testing, not shipped silently:** the family revoke is a `@Modifying` bulk `UPDATE`, executed inside `rotate()`'s own `@Transactional` method — which then immediately throws `RefreshTokenReuseException`. Spring rolls back the *entire* transaction on that unchecked exception, undoing the revoke along with everything else, so the sibling token stayed alive. Fixed by moving the revoke into a new `RefreshTokenFamilyRevocationService.revokeFamily(familyId)`, `@Transactional(propagation = REQUIRES_NEW)`, called from `rotate()` through a real Spring proxy (a separate bean — self-invocation within the same class would have silently bypassed `REQUIRES_NEW` entirely) so it commits before the throw. First test run caught this precisely (401 expected, got 200); the fix is what's now in the tree.
- `AuthController.refresh` now calls `rotate(...)` and returns the new raw token in the response (`AuthResponse.refreshToken`, a new field). `AuthController.logout`'s call to `revokeRefreshToken` is unchanged at the call site (hashing happens inside the service).
- New `InvalidRefreshTokenException` (401) for not-found/revoked/expired, alongside `RefreshTokenReuseException` (401) — both previously would have fallen through to the generic `Exception.class` handler as a 500 (the raw `RuntimeException`s the old code threw).

## C5 — Google `email_verified`

`GoogleTokenVerifier.verify()` checked `aud` but never read `email_verified`. Added the check after the existing `aud`/`sub`/`email` checks: rejects (existing `InvalidGoogleTokenException`, 401) unless `email_verified` is `true` — handling both the string `"true"` (Google's documented tokeninfo response shape) and a real JSON boolean defensively, since the value's shape isn't something to assume. **Missing entirely is treated as unverified (fail closed)** — an absent claim doesn't mean verified.

## Files changed, and why

**New:**
- `src/main/java/com/aetherops/security/ClientIpResolver.java` — C1, shared XFF resolution
- `src/main/java/com/aetherops/auth/service/TwoFactorAttemptService.java` — C2, per-partial-token lockout (Redis)
- `src/main/java/com/aetherops/common/exception/TwoFaSessionLockedException.java` — C2, 423 on locked 2FA session
- `src/main/java/com/aetherops/common/exception/InvalidRefreshTokenException.java` — C4, 401 for not-found/revoked/expired
- `src/main/java/com/aetherops/common/exception/RefreshTokenReuseException.java` — C4, 401 on reuse
- `src/main/java/com/aetherops/auth/service/RefreshTokenFamilyRevocationService.java` — C4, `REQUIRES_NEW` family revoke (see bug note above)
- `src/main/resources/db/migration/V64__refresh_token_rotation.sql` — C4 schema change
- `src/test/java/com/aetherops/auth/service/GoogleTokenVerifierTest.java` — C5 required test
- `src/test/java/com/aetherops/auth/RefreshTokenRotationIntegrationTest.java` — C4 required test

**Modified:**
- `src/main/java/com/aetherops/common/exception/GlobalExceptionHandler.java` — registered the 3 new exception handlers above
- `src/main/java/com/aetherops/ratelimit/IpRateLimitFilter.java` — uses `ClientIpResolver`; added `/api/v1/auth/2fa/verify` to `isProtectedPath`
- `src/main/java/com/aetherops/security/RateLimitResponseFilter.java` — uses `ClientIpResolver`
- `src/main/resources/application.yml` — added `app.security.trusted-proxy-count` (default 1)
- `src/main/java/com/aetherops/auth/service/TotpService.java` — checks/records `TwoFactorAttemptService` around code verification
- `src/main/java/com/aetherops/auth/service/LoginAttemptService.java` — full rewrite: Redis-backed, fail-closed
- `src/main/java/com/aetherops/auth/RefreshToken.java` — `token`→`tokenHash`, added `familyId`, `used`
- `src/main/java/com/aetherops/auth/repository/RefreshTokenRepository.java` — `findByTokenHash`, `revokeFamily`
- `src/main/java/com/aetherops/auth/service/RefreshTokenService.java` — full rewrite: `issue`/`rotate`, hashing, family logic
- `src/main/java/com/aetherops/auth/controller/AuthController.java` — `/refresh` uses `rotate`, returns new token
- `src/main/java/com/aetherops/auth/model/AuthResponse.java` — added `refreshToken` field
- `src/main/java/com/aetherops/auth/service/GoogleTokenVerifier.java` — `email_verified` check
- `src/test/java/com/aetherops/ratelimit/IpRateLimitFilterTest.java` — updated constructor (new `ClientIpResolver` dependency); fixed an assertion that had encoded the old, buggy leftmost-entry behavior; added the required forged-chain test
- `src/test/java/com/aetherops/auth/RefreshTokenServiceTest.java` — full rewrite for the new `issue`/`rotate` API
- `src/test/java/com/aetherops/auth/LoginAttemptServiceTest.java` — converted to a Redis-backed integration test (extends `AbstractIntegrationTest`) since the service is no longer pure in-memory
- `src/test/java/com/aetherops/auth/TotpIntegrationTest.java` — added the required 2FA-lockout test plus one proving the lock is per-session, not per-account

No test was skipped, disabled, or weakened. Nothing outside the six items above (C0–C5) was touched.

## Full `mvn test`

```
Tests run: 864, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

(Docker Desktop was running throughout, per instruction.)

## New/changed tests confirmed by name, from surefire XML (all `errors="0" failures="0"`)

**`TEST-com.aetherops.auth.RefreshTokenRotationIntegrationTest.xml`** (new file, required test included):
- `refresh_validToken_rotatesAndReturnsNewToken`
- `refresh_sameTokenTwice_secondCallRejected`
- `refresh_reusedToken_revokesEntireFamily_forcingReLogin` ← required test
- `refresh_unknownToken_returns401`

**`TEST-com.aetherops.auth.service.GoogleTokenVerifierTest.xml`** (new file, required test included):
- `verify_emailVerifiedMissing_throwsInvalidGoogleTokenException`
- `verify_emailVerifiedFalseString_throwsInvalidGoogleTokenException` ← required test
- `verify_emailVerifiedTrueString_succeeds`
- `verify_emailVerifiedBooleanTrue_succeeds`

**`TEST-com.aetherops.ratelimit.IpRateLimitFilterTest.xml`** (updated):
- `usesXForwardedFor_takesTrustedEntry_notLeftmostAttackerControlledOne`
- `forgedLeftmostEntry_doesNotChangeResolvedKey_soBucketIsNotReset` ← required test
- (plus the 3 pre-existing, unchanged: `allowsRequest_whenUnderLimit`, `blocksRequest_whenOverLimit`, `skipsFilter_forNonProtectedPath`)

**`TEST-com.aetherops.auth.TotpIntegrationTest.xml`** (updated, new methods among 18 total):
- `verify_5FailedAttempts_locksSessionEvenWithCorrectCode` ← required test
- `verify_lockedSession_freshLoginGetsFreshPartialTokenThatWorks`

**`TEST-com.aetherops.auth.RefreshTokenServiceTest.xml`** (rewritten, 7 methods):
`issue_savesHashedTokenWithNewFamily_returnsRawToken`, `rotate_validToken_marksOldUsedAndIssuesNewInSameFamily`, `rotate_alreadyUsedToken_revokesFamilyAndThrowsReuseException`, `rotate_revokedToken_throwsInvalidRefreshTokenException`, `rotate_expiredToken_throwsInvalidRefreshTokenException`, `rotate_unknownToken_throwsInvalidRefreshTokenException`, `revokeAllUserTokens_deletesAllUserTokens`

**`TEST-com.aetherops.auth.LoginAttemptServiceTest.xml`** (converted to integration test, 6 methods, all passing against real Testcontainers Redis):
`newEmail_isNotLocked`, `fourFailures_notYetLocked`, `fiveFailures_isLocked`, `lockedAccount_minutesUntilUnlock_isPositive`, `successResetsCounter`, `expiredLock_isNotLocked`

## Things I was unsure about / flagged rather than deciding alone

1. **C1's index formula** — flagged with a worked counter-example before implementing (see above); you confirmed `chain.length - trustedProxyCount`.
2. **Refresh tokens are never issued anywhere today** (C0 finding) — noted, not fixed; it's outside C4's literal scope and is a separate product decision (which flows issue one, and the frontend has no refresh-on-401 handling per Step B).
3. **The transaction-rollback bug in family revocation** — not a pre-existing bug, but one I introduced and then caught via the required test failing; documented above and in the code (`RefreshTokenFamilyRevocationService`'s javadoc) so it doesn't get "fixed" back into the broken shape later.
4. **`/api/v1/auth/refresh` and `/api/v1/auth/reset-password`** — checked per your instruction, concluded no code-guessing gap exists (both use full-entropy tokens), so left unmodified rather than adding them to `isProtectedPath` without cause.
