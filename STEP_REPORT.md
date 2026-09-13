# STEP G3 — Token Hashing, Config, and Key Separation (Phase 3 findings 2, 3, 4)

Status: **implemented, not committed.** All three findings closed; details, tests, and
one flagged-but-not-fixed pre-existing issue below.

---

## Finding 2 — email verification tokens hashed at rest

**Pattern followed:** `PasswordResetService`'s `sha256(String)` helper (`MessageDigest`
+ `HexFormat` hex encoding) — chosen over `RefreshTokenService`'s identical copy,
`ApiKeyService`'s identical copy, and `InvitationTokenGenerator`'s (Base64-url-encoded)
variant because `PasswordResetService`'s use case is closest in shape: a single-use,
time-limited token embedded in an emailed link, looked up once, cleared after use —
exactly what `EmailVerificationService` already does. (Worth noting for later: all
four existing copies are byte-for-byte duplicates of each other except
`InvitationTokenGenerator`'s encoding choice — real duplication, same shape as the
AES/GCM duplication G2 fixed. Out of scope here since it wasn't one of findings 2/3/4,
but flagging it as a candidate for the same treatment.)

**Migration — confirmed safe to rename rather than transform:**
`src/main/resources/db/migration/V70__hash_email_verification_token.sql` does
`ALTER TABLE users RENAME COLUMN email_verification_token TO email_verification_token_hash`,
same shape as `V64__refresh_token_rotation.sql`'s precedent for refresh tokens. Confirmed
safe from the schema, not just re-asserted from memory: grepped every migration file for
`email_verification_token` — only `V33__add_email_verification.sql` (the original
`ADD COLUMN` + partial unique index) and this new one touch it; no foreign key, no other
index, no other migration references it. Combined with G2's already-established fact that
no environment has ever actually been deployed (Railway account suspended), a plain
rename carries zero data-loss risk.

**Code changes:**
- `User.java` — field/column renamed `emailVerificationToken` → `emailVerificationTokenHash`
  (`email_verification_token_hash`, still `VARCHAR(64)` — already the right width for a
  SHA-256 hex digest, no length change needed).
- `UserRepository.findByEmailVerificationToken` → `findByEmailVerificationTokenHash`.
- `EmailVerificationService` — `sendVerificationEmail` now stores `sha256(token)`, not
  `token`; `verifyEmail` looks up by `sha256(token)`. Raw-token generation (32 random
  bytes, hex-encoded, 24h expiry) is unchanged — only what gets persisted changed. The
  raw token still goes out in the email link exactly as before.

**Tests:**
- `EmailVerificationServiceTest.sendVerificationEmail_persistsOnlyHash_neverRawToken_andVerificationStillWorksEndToEnd`
  (new) — captures the actual link handed to `EmailService`, extracts the raw token,
  asserts the persisted value is neither blank nor equal to the raw token but does equal
  `sha256(rawToken)`, then feeds the raw token back into `verifyEmail` and asserts it
  succeeds (user verified, hash cleared). This is the "raw token never persisted;
  verification still works end to end" test.
- `verifyEmail_validToken_setsVerifiedAndClearsToken` / `verifyEmail_expiredToken_...`
  (existing, updated) — now build/mock against the hash, not the raw token.
- `AuthIntegrationTest.register_createsUserWithUnverifiedEmail` — one-line rename
  (`getEmailVerificationToken()` → `getEmailVerificationTokenHash()`), same assertion
  (not blank).

---

## Finding 3 — docker-compose env var names, hardcoded JWT fallback, stale encryption keys

**Renamed** (`docker-compose.yml`, `aetherops` service): `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`
→ `SPRING_DATASOURCE_URL`/`SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD` — the
names Spring's relaxed binding actually reads. The Postgres-container-derived defaults
(`${POSTGRES_USER:-aetherops_user}` etc.) are unchanged; that's a local-dev convenience
self-contained within this file, not the leaked-secret pattern the JWT var had, and finding
3 didn't ask me to touch it.

**JWT fallback removed, name fixed, and made fail-closed in the same change** (per your
explicit instruction not to split the rename from the fallback removal):
`JWT_SECRET: ${JWT_SECRET:-aetherops-local-dev-secret-key-minimum-32-chars}` →
`APP_JWT_SECRET: ${APP_JWT_SECRET:?Set APP_JWT_SECRET in .env (32+ chars) — see .env.example}`.
Docker Compose's `${VAR:?message}` syntax refuses to start the container at all if the var
is unset/empty, printing that message — verified this actually works (see Verification
below). The old hardcoded value is gone from the file entirely, not just unreferenced.

**Superseded encryption key names — checked, none existed to rename or remove.**
Grepped `docker-compose.yml` for `ENCRYPTION` before touching it: it set **none** of the
four AES/GCM keys, neither the pre-G2 single-key names nor the post-G2 `_KEYS_n` names. So
the literal instruction ("update them to the `_KEYS_1` form or remove them") had nothing to
act on — but since G2 made these hard-required at Spring startup, `docker compose up` would
have failed to boot the app entirely for a reason unrelated to the DB/JWT mismatches. I
flagged this as a judgment call and you confirmed: added all four, fail-closed, no
hardcoded values, same `${VAR:?...}` pattern as JWT:
`APP_SLACK_TOKEN_ENCRYPTION_KEYS_1`, `APP_CALENDAR_TOKEN_ENCRYPTION_KEYS_1`,
`APP_INTEGRATIONS_TOKEN_ENCRYPTION_KEYS_1`, `APP_TOTP_ENCRYPTION_KEYS_1`.

**Also added, per your follow-up message:** `APP_SLACK_OAUTH_STATE_SECRET` (new in this
step, finding 4) and `APP_CALENDAR_OAUTH_STATE_SECRET` (pre-existing requirement that was
already missing from this file for an unrelated, pre-G3 reason — see the flagged issue
below). Same fail-closed syntax as everything else.

**Error messages now name the exact variable and constraint** (your follow-up ask):
- `VersionedAesGcmEncryptor.buildKeySpec(String key, String envVarName)` — signature
  changed from `(key, version, purpose)` to take the exact env var name directly. Message:
  `"<ENV_VAR> is required (exactly 32 characters)"`, e.g.
  `"APP_TOTP_ENCRYPTION_KEYS_1 is required (exactly 32 characters)"` — your exact example.
  Each of the four encryptor classes now builds that name itself
  (`ENV_VAR_PREFIX + version`, e.g. `"APP_SLACK_TOKEN_ENCRYPTION_KEYS_" + version`).
- `SlackOAuthStateService` / `CalendarOAuthStateService` blank-secret checks now say
  `"APP_SLACK_OAUTH_STATE_SECRET is required (must not be blank)"` /
  `"APP_CALENDAR_OAUTH_STATE_SECRET is required (must not be blank)"` (previously named
  the Spring property path, e.g. `app.calendar.oauth-state-secret`, not the env var).
- `TotpSecretEncryptor`'s insecure-default warning referenced the already-stale
  `TOTP_ENCRYPTION_KEY` (never the real property name even before G2); corrected to name
  the actual current env var (`APP_TOTP_ENCRYPTION_KEYS_{current}`).

**`.env.example` updated to match** (per your follow-up ask) — added all of the above
with a one-line comment above the encryption keys on generating one
(`openssl rand -hex 16` → 16 bytes → 32 hex characters, verified by byte-counting an
actual generated example). **Deliberately did not use realistic-looking generated
values as the placeholders** — I generated some initially, then reconsidered: a
plausible-looking 32-char secret sitting in this committed file is exactly the pattern
finding 3 itself warns about (a secret that looks live, in git history, that someone
might forget to replace). Used obviously-non-functional placeholders instead
(`REPLACE_WITH_OUTPUT_OF_openssl_rand_hex_16`, `REPLACE_WITH_YOUR_OWN_RANDOM_SECRET`) —
for the length-constrained ones, copy-pasting the file unedited fails closed (wrong
length) rather than silently running with a shared secret. Also fixed the same
`DB_URL`/`DB_USERNAME`/`DB_PASSWORD` stale names in `.env.example`'s "local development
only" section (→ `SPRING_DATASOURCE_*`) — same bug as docker-compose.yml, same fix.

**Flagged, not fixed (outside findings 2/3/4):** `docker-compose.yml` has a pre-existing,
unrelated YAML structural bug — `zipkin:` is nested under `volumes:` instead of being a
sibling service, so `docker compose config`/`up` fails regardless of anything in this
step (`volumes.pgdata additional properties 'zipkin' not allowed`). Verified this is the
*only* remaining error once all required env vars are supplied (see Verification below) —
my changes are otherwise syntactically complete and correct. Did not fix it: it's not a
secret/env-var naming issue, doesn't touch findings 2/3/4, and you said fix nothing
outside them. Separately, `kubernetes/deployment.yaml`/`secret.yaml` still reference the
old single-key TOTP env var shape (`APP_TOTP_ENCRYPTIONKEY`, itself already missing an
underscore even before G2) — flagged in the G2 report already, still unfixed, still not
part of this step's scope (Railway, not Kubernetes, is the real deployment target).

---

## Finding 4 — SlackOAuthStateService gets its own key

**Change:** `SlackOAuthStateService` no longer takes `SlackAppConfig` (and therefore no
longer reads `app.slack.signing-secret`, the Slack-provided value
`SlackRequestVerifier` uses to authenticate inbound requests). It now takes its own
`@Value("${app.slack.oauth-state-secret}") String secret` directly, builds its own
`SecretKeySpec`, and fails closed exactly like `CalendarOAuthStateService` — the pattern
you named — throwing `IllegalArgumentException` if blank/unset, no default anywhere.

**New env var:** `APP_SLACK_OAUTH_STATE_SECRET` (property `app.slack.oauth-state-secret`).
No format/length constraint (it's an HMAC key, not AES — any length works), but treat it
like every other secret here: long, random, unique per environment, never reused. Added
to `.env.example` and `docker-compose.yml` (see Finding 3 above) and to
`application.yml`'s comments alongside `app.calendar.oauth-state-secret`.

**Test proving it validates under the new dedicated key** (your requirement):
`SlackOAuthStateServiceTest` now constructs the service with `OAUTH_STATE_SECRET`, a
value deliberately different from `SIGNING_SECRET` in the same file. Two tests cover
this:
- `issueThenVerify_roundTrip_returnsSameTenantId` — the direct proof: issue and verify
  both go through the dedicated key end to end.
- `verify_rejectsStateSignedWithSlackSigningSecret_provingKeysAreIndependent` (new) —
  crafts a state signed with what `SIGNING_SECRET` would have been (the old shared
  value) and asserts it's now rejected. Before this fix, since both values used to be
  the same underlying `SlackAppConfig.getSigningSecret()`, this exact input would have
  validated. This is the test that actually proves the coupling is gone, not just that
  the happy path still works.
- `constructor_rejectsBlankSecret` (new, mirrors `CalendarOAuthStateService`'s existing
  test) — proves the fail-closed behavior.

---

## Files changed

**New:**
- `src/main/resources/db/migration/V70__hash_email_verification_token.sql`

**Changed — main:**
- `src/main/java/com/aetherops/auth/model/User.java`
- `src/main/java/com/aetherops/auth/repository/UserRepository.java`
- `src/main/java/com/aetherops/auth/service/EmailVerificationService.java`
- `src/main/java/com/aetherops/auth/service/TotpSecretEncryptor.java` (env-var-named
  error messages, buildKeySpec signature)
- `src/main/java/com/aetherops/calendar/CalendarOAuthStateService.java` (message only)
- `src/main/java/com/aetherops/calendar/CalendarTokenEncryptor.java` (buildKeySpec signature)
- `src/main/java/com/aetherops/crypto/VersionedAesGcmEncryptor.java` (`buildKeySpec` now
  takes the exact env var name)
- `src/main/java/com/aetherops/integrations/service/TicketIntegrationTokenEncryptor.java`
  (buildKeySpec signature)
- `src/main/java/com/aetherops/slack/SlackOAuthStateService.java` (dedicated key, finding 4)
- `src/main/java/com/aetherops/slack/SlackTokenEncryptor.java` (buildKeySpec signature)
- `src/main/resources/application.yml` (comments)
- `docker-compose.yml`
- `.env.example`

**Changed — test:**
- `src/test/java/com/aetherops/AbstractIntegrationTest.java` (+ `app.slack.oauth-state-secret`)
- `src/test/java/com/aetherops/auth/AuthIntegrationTest.java` (rename)
- `src/test/java/com/aetherops/auth/EmailVerificationServiceTest.java` (rewritten)
- `src/test/java/com/aetherops/crypto/VersionedAesGcmEncryptorTest.java`
  (`buildKeySpec` call sites + new error-message test)
- `src/test/java/com/aetherops/slack/SlackOAuthStateServiceTest.java` (rewritten)
- `src/test/resources/application-test.yml` (+ `app.slack.oauth-state-secret`)

**Not touched, unrelated pre-existing edit still in your working tree:** `railway.toml`.

## New tests, by name

- `EmailVerificationServiceTest.sendVerificationEmail_persistsOnlyHash_neverRawToken_andVerificationStillWorksEndToEnd`
- `VersionedAesGcmEncryptorTest.buildKeySpec_errorMessage_namesExactEnvVarAndConstraint`
- `SlackOAuthStateServiceTest.verify_rejectsStateSignedWithSlackSigningSecret_provingKeysAreIndependent`
- `SlackOAuthStateServiceTest.constructor_rejectsBlankSecret`

## Verification

Docker is not running locally (confirmed via `docker info`, same as G2) — the
Testcontainers-backed suite (`AbstractIntegrationTest` and everything extending it)
cannot execute here. Ran everything that can run without it:

- `mvn test-compile` — clean, full main + test tree.
- Targeted: every unit test class touched or exercising touched code —
  `VersionedAesGcmEncryptorTest`, `SlackTokenEncryptorTest`, `CalendarTokenEncryptorTest`,
  `TicketIntegrationTokenEncryptorTest`, `TotpSecretEncryptorTest`,
  `SlackTokenEncryptionPropertiesTest`, `EmailVerificationServiceTest`,
  `SlackOAuthStateServiceTest`, `CalendarOAuthStateServiceTest`, `SlackRequestVerifierTest`
  → **51 tests, 0 failures, 0 errors.**
- Full `mvn test -Dmaven.test.failure.ignore=true`: **1065 run, 0 failures, 290 errors** —
  same 290-error Docker-unavailability signature as G2's baseline run (every one is
  `NoClassDefFoundError` ← `Could not find a valid Docker environment`), 6 more tests run
  than G2's baseline (the new ones above), zero new failures anywhere.
- `docker compose config` (doesn't need the daemon, just validates/renders the file):
  confirmed the new fail-closed env vars work exactly as intended — with none of them
  set, it refuses with the exact custom message
  (`required variable APP_JWT_SECRET is missing a value: Set APP_JWT_SECRET in .env...`);
  with all seven required vars set, the only remaining error is the pre-existing
  `zipkin`/`volumes` YAML bug flagged above, confirming my changes are otherwise
  syntactically correct.

Not verified in this session (needs Docker): the app actually booting end-to-end via
`docker compose up` with real values, and the Testcontainers-backed integration suites
(`TotpIntegrationTest`, `AuthIntegrationTest`, etc.) exercising these changes through a
real Spring context + Postgres. Per your note, say so rather than run it — ready for you
to push to CI for that verification.

Nothing committed.
