# STEP G4 — Crypto Cleanup (Phase 3 findings 5, 6, 7, 8 + BCrypt bump)

Status: **implemented, not committed.** All five items closed. Finding 7 was
investigated and found NOT to be a real issue in this codebase's Spring version —
details below.

---

## Finding 5 — constant-time backup code comparison

`TotpService.java` line 129: `backup.getCodeHash().equals(codeHash)` →
`MessageDigest.isEqual(backup.getCodeHash().getBytes(UTF_8), codeHash.getBytes(UTF_8))`,
matching every HMAC comparison already in this codebase (`SlackOAuthStateService`,
`CalendarOAuthStateService`, `WebhookSignatureVerifier`, etc.).

**Test:** no new unit test added — there's no existing `TotpServiceTest` unit-test
file to extend (only `TotpIntegrationTest`, Docker-gated), and per the finding
itself this is a "Low, consistency-only" fix with no behavior change:
`MessageDigest.isEqual` and `String.equals` return identical true/false results for
every input, just not in constant time. `TotpIntegrationTest`'s existing
`verify_validBackupCode_returnsFullJwt` and `verify_usedBackupCode_returns400` (both
Docker-gated, not run in this session) already exercise this exact branch and will
continue to pass unchanged — this is a correctness-preserving substitution, not new
behavior to cover.

---

## Finding 6 — redact secrets from DTO toString()

**The three named DTOs** (`LoginRequest`, `RegisterRequest`, `ResetPasswordRequest`)
now override `toString()`, redacting `password`/`newPassword`/`token`.

**Checked every other DTO for a secret, token, or key field** (grepped `model/*.java`
for `password|token|secret|apiKey|code|...`-shaped fields) and found nine more that
qualify. Fixed all of them:

| DTO | Field(s) redacted |
|---|---|
| `GoogleAuthRequest` | `idToken`, `invitationToken` |
| `RefreshTokenRequest` | `refreshToken` |
| `LogoutRequest` | `refreshToken` |
| `TwoFaDisableRequest` | `password` |
| `TwoFaVerifyRequest` | `partialToken`, `code` |
| `TwoFaVerifySetupRequest` | `code` |
| `TwoFaSetupResponse` | `secret`, `backupCodes`, **and `qrCodeUrl`** — it embeds the raw TOTP secret as a query param (`otpauth://...?secret=<raw>`), so it's just as sensitive as the `secret` field itself |
| `AuthResponse` | `token`, `refreshToken`, `partialToken` |
| `CreateApiKeyResponse` | `plaintext` (the one-time full API key; `keyPrefix` stays visible — it's meant to be shown) |
| `ConfigureTicketIntegrationRequest` | `apiToken` |

`RegisterRequest` also got `invitationToken` redacted alongside `password` (the
finding named only "the password field" for this one, but `invitationToken` is a
single-use tenant-joining credential on the same DTO — same category, so I treated
it the same way rather than leaving a partial fix on a DTO I was already editing).

**Considered and deliberately excluded:** `CloudWatchSnsRequest.token`. This is
AWS SNS's own subscription-confirmation field name, not a user credential — it's
single-use, tied to confirming/unsubscribing one specific topic subscription, and
this class (`@Data`, no existing `toString()` override) represents an inbound
webhook envelope where logging the full payload is a plausible, legitimate
operational need for debugging alarm delivery. Judgment call, not explicitly
covered by the finding's "secret, token, or key" wording taken literally — flagging
it here rather than silently including or excluding it. Say the word if you want it
redacted too.

**Tests (new):**
- `ToStringRedactsSecretsTest` (12 test methods, one per DTO above plus
  `LoginRequest`/`RegisterRequest`/`ResetPasswordRequest`, plus one documenting
  `ForgotPasswordRequest` was checked and has no secret field to redact) —
  `src/test/java/com/aetherops/auth/model/ToStringRedactsSecretsTest.java`
- `CreateApiKeyResponseTest.toString_redactsPlaintext_keepsPrefixVisible`
- `ConfigureTicketIntegrationRequestTest.toString_redactsApiToken_keepsOtherFieldsVisible`

---

## Finding 7 — Google token in URL / logged on failure (UNVERIFIED → RESOLVED, no leak)

**Investigated as instructed rather than assumed.** Wrote
`GoogleTokenVerifierTest.verify_networkFailure_neverLogsRawToken`: forces a real
`RestClientException` from the exact call path (`MockRestServiceServer` throws
`IOException` from the response creator, so `RestTemplate` wraps it into a
`ResourceAccessException`), attaches a Logback `ListAppender` to
`GoogleTokenVerifier`'s logger (same pattern `EmailServiceTest` already uses), and
asserts the captured `log.warn(..., e.getMessage())` output never contains the
raw token.

**Ran it before making any change**, to observe real behavior rather than reason
about it: it **passed immediately, with zero code changes**. Logged message:
```
Google token verification failed: I/O error on GET request for "https://oauth2.googleapis.com/tokeninfo": Connection refused
```
No query string, no token — contradicting what I (and apparently the original
audit) expected from the well-known older `ResourceAccessException` message format.

**Root-caused it, not just observed it**, since a passing test alone doesn't prove
*why*: decompiled `RestTemplate.class` from this project's actual dependency
(`spring-web 7.0.5`) and confirmed `createResourceAccessException` is invoked with
the resolved request `URI`, so in principle the query string *is* available to it —
then independently verified with a **real** (non-mocked) `RestTemplate` against an
actually-unreachable address (`http://127.0.0.1:1`, real `Connection refused`, no
`MockRestServiceServer` involved) and got the identical query-string-free message.
So this isn't a `MockRestServiceServer` quirk — Spring 7.0.5's actual
`ResourceAccessException` message construction does not include the query string
in this failure path, at least for a connection-refused-class I/O error.

**Which way it went, concretely: the token does NOT leak.** No production code
change was needed or made. The test stays as a regression guard — if a future
Spring upgrade changes this message format back to including the full URI, this
test will start failing and catch it before it ships.

(I deleted a throwaway diagnostic test/scratch directory I used to independently
confirm the real-RestTemplate behavior — not part of the diff, nothing left
behind.)

---

## Finding 8 — JWT clock-skew tolerance

`JwtService.getClaims()`: `Jwts.parser().verifyWith(key)` → `...verifyWith(key)
.clockSkewSeconds(30).build()...` (jjwt 0.12.6's current fluent API; the older
`setAllowedClockSkewSeconds` also exists but `.clockSkewSeconds(...)` matches the
existing builder-chain style already in this method).

**Existing test had to be fixed, not just extended** — `isTokenValid_withExpiredToken_returnsFalse`
built its "expired" token via a 0-hour-expiry `JwtService` (`exp` ≈ token-creation
instant). With 30s of tolerance now in place, that token would fall *inside* the
new tolerance window and the test would start asserting the wrong thing (still
technically pass by accident today, but become a race against wall-clock timing,
and wrong in spirit — it would no longer be testing "expired is rejected", it'd be
testing "expired-by-a-few-milliseconds is rejected", which is no longer true).
Per "never weaken a test": I did not delete or loosen the assertion — I rebuilt it
so it constructs a token whose `exp` is unambiguously in the past (60s, comfortably
beyond the 30s tolerance) using jjwt directly against the same signing key,
renamed to `isTokenValid_expiredBeyondClockSkewTolerance_returnsFalse` for clarity,
and kept the exact same assertion (`isTokenValid(...)` is `false`). Net effect:
same coverage, now actually correct given the new behavior, not weaker.

**New tests:**
- `isTokenValid_expiredBeyondClockSkewTolerance_returnsFalse` (rebuilt, see above)
- `isTokenValid_expiredWithinClockSkewTolerance_returnsTrue` — the actual point of
  the fix: a token that expired only 10s ago (well inside the 30s tolerance) is
  still accepted.

---

## BCrypt bump (10 → 12)

`PasswordEncoderConfig`: `new BCryptPasswordEncoder()` → `new BCryptPasswordEncoder(12)`.

**Test proving old hashes still verify** (the whole risk of this change, per your
instruction): `PasswordEncoderConfigTest.passwordHashedAtOldStrengthTen_stillAuthenticatesUnderNewEncoder`
— hashes a password with an independently-constructed strength-10 encoder, then
confirms the new strength-12 bean's `.matches(...)` still returns `true` against
that old hash. A second test,
`newlyEncodedPassword_usesStrengthTwelve`, parses the BCrypt hash format
(`$<version>$<cost>$...`) and asserts the cost factor is actually `12` for newly
encoded passwords — confirming the bump took effect, not just that old hashes
still work.

---

## Files changed

**Main:**
- `src/main/java/com/aetherops/auth/service/TotpService.java` (finding 5)
- `src/main/java/com/aetherops/auth/model/LoginRequest.java`,
  `RegisterRequest.java`, `ResetPasswordRequest.java`, `GoogleAuthRequest.java`,
  `RefreshTokenRequest.java`, `LogoutRequest.java`, `TwoFaDisableRequest.java`,
  `TwoFaVerifyRequest.java`, `TwoFaVerifySetupRequest.java`, `TwoFaSetupResponse.java`,
  `AuthResponse.java` (finding 6)
- `src/main/java/com/aetherops/apikey/model/CreateApiKeyResponse.java`,
  `src/main/java/com/aetherops/integrations/model/ConfigureTicketIntegrationRequest.java`
  (finding 6)
- `src/main/java/com/aetherops/auth/service/JwtService.java` (finding 8)
- `src/main/java/com/aetherops/config/PasswordEncoderConfig.java` (BCrypt bump)
- *(no production change for finding 7 — investigated, confirmed not an issue)*

**Test — new:**
- `src/test/java/com/aetherops/auth/model/ToStringRedactsSecretsTest.java`
- `src/test/java/com/aetherops/apikey/model/CreateApiKeyResponseTest.java`
- `src/test/java/com/aetherops/integrations/model/ConfigureTicketIntegrationRequestTest.java`
- `src/test/java/com/aetherops/config/PasswordEncoderConfigTest.java`

**Test — changed:**
- `src/test/java/com/aetherops/auth/service/GoogleTokenVerifierTest.java` (finding 7
  investigation test + Logback appender wiring)
- `src/test/java/com/aetherops/auth/JwtServiceTest.java` (finding 8 — rebuilt the
  expired-token test, added the within-tolerance test)

**Not touched, unrelated pre-existing edit still in your working tree:** `railway.toml`.

## New/changed tests, by name

- `ToStringRedactsSecretsTest` — 12 methods (see Finding 6 above)
- `CreateApiKeyResponseTest.toString_redactsPlaintext_keepsPrefixVisible`
- `ConfigureTicketIntegrationRequestTest.toString_redactsApiToken_keepsOtherFieldsVisible`
- `GoogleTokenVerifierTest.verify_networkFailure_neverLogsRawToken`
- `JwtServiceTest.isTokenValid_expiredBeyondClockSkewTolerance_returnsFalse` (rebuilt)
- `JwtServiceTest.isTokenValid_expiredWithinClockSkewTolerance_returnsTrue`
- `PasswordEncoderConfigTest.passwordHashedAtOldStrengthTen_stillAuthenticatesUnderNewEncoder`
- `PasswordEncoderConfigTest.newlyEncodedPassword_usesStrengthTwelve`

## Verification

Docker is not running locally (confirmed via `docker info`, same as G2/G3) — the
Testcontainers-backed suite still can't execute here. Ran everything that can run
without it:

- `mvn test-compile` — clean, full main + test tree.
- Targeted: every new/changed test class —
  `GoogleTokenVerifierTest`, `JwtServiceTest`, `PasswordEncoderConfigTest`,
  `ToStringRedactsSecretsTest`, `CreateApiKeyResponseTest`,
  `ConfigureTicketIntegrationRequestTest` → **31 tests, 0 failures, 0 errors.**
- Full `mvn test -Dmaven.test.failure.ignore=true`: **1083 run, 0 failures, 290
  errors** — identical 290-error Docker-unavailability signature as every prior
  step's baseline (all `NoClassDefFoundError` ← `Could not find a valid Docker
  environment`), 18 more tests run than G3's baseline, zero new failures anywhere.

Not verified in this session (needs Docker): `TotpIntegrationTest`'s backup-code
tests exercising the finding-5 change through a real Spring context + Postgres;
`AuthIntegrationTest`/`PasswordResetIntegrationTest`/`GoogleOAuthIntegrationTest`
exercising findings 6/8/BCrypt end-to-end through real HTTP requests. Ready to push
to CI for that verification, as in prior steps.

Nothing committed.
