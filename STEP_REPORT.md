# STEP G2 — Key Versioning for Rotation — Recon + Design (Phase 3, finding 1)

Status: **recon complete, design proposed, nothing implemented yet.**

## 1. Are the four encryptors structurally identical?

Yes — byte-for-byte identical logic, only the `@Value` property name, the
exception message strings, and (in `TotpSecretEncryptor`) an extra insecure-default
warning differ.

| Class | File | Key property |
|---|---|---|
| `SlackTokenEncryptor` | `src/main/java/com/aetherops/slack/SlackTokenEncryptor.java` | `app.slack.token-encryption-key` |
| `CalendarTokenEncryptor` | `src/main/java/com/aetherops/calendar/CalendarTokenEncryptor.java` | `app.calendar.token-encryption-key` |
| `TicketIntegrationTokenEncryptor` | `src/main/java/com/aetherops/integrations/service/TicketIntegrationTokenEncryptor.java` | `app.integrations.token-encryption-key` |
| `TotpSecretEncryptor` | `src/main/java/com/aetherops/auth/service/TotpSecretEncryptor.java` | `app.totp.encryption-key` |

Each independently declares:

```java
private static final String ALGORITHM = "AES/GCM/NoPadding";
private static final int GCM_IV_LENGTH = 12;
private static final int GCM_TAG_LENGTH = 128;
private final SecretKeySpec keySpec;   // built from a 32-byte @Value string, AES-256

public String encrypt(String plaintext) {
    byte[] iv = new byte[GCM_IV_LENGTH];
    new SecureRandom().nextBytes(iv);
    Cipher cipher = Cipher.getInstance(ALGORITHM);
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
    byte[] ciphertext = cipher.doFinal(plaintext.getBytes(UTF_8));
    byte[] combined = new byte[GCM_IV_LENGTH + ciphertext.length];
    System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
    System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.length);
    return Base64.getEncoder().encodeToString(combined);
}

public String decrypt(String encoded) {
    byte[] combined = Base64.getDecoder().decode(encoded);
    byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
    byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
    Cipher cipher = Cipher.getInstance(ALGORITHM);
    cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
    return new String(cipher.doFinal(ciphertext), UTF_8);
}
```

They share **no common base class or utility** — each file has its own copy of
`ALGORITHM`/`GCM_IV_LENGTH`/`GCM_TAG_LENGTH` and its own encrypt/decrypt bodies.
That's real duplication: today it means four places to patch for any bug (e.g.
the IV must never repeat under a key — `SecureRandom` per-call is fine, but it's
duplicated fine, not shared-and-verified-once), and it's exactly why the missing
key-id was allowed to happen four times over instead of once.

## 2. Exact stored format today

Confirmed: `Base64Encode(IV || GCM-output)`.

Precise byte layout of the decoded blob:

```
byte[0..11]   IV                  (12 bytes, random per encryption)
byte[12..N]   GCM-output          (ciphertext_len bytes of actual ciphertext,
                                    followed by a 16-byte GCM authentication tag —
                                    Cipher.doFinal() with GCMParameterSpec(128, iv)
                                    appends the tag to the ciphertext automatically)
```

So total length = 12 (IV) + plaintext_len + 16 (tag). There is no length prefix,
no version byte, no algorithm identifier — just IV immediately followed by
tagged ciphertext, then the whole thing Base64-encoded. Nothing in the blob
says which key produced it.

## 3. Database columns holding these values

| Table | Column | Type | Encryptor | Written from |
|---|---|---|---|---|
| `slack_workspaces` | `encrypted_bot_token` | `TEXT NOT NULL` | `SlackTokenEncryptor` | `SlackOAuthController` |
| `user_calendar_connections` | `encrypted_refresh_token` | `TEXT NOT NULL` | `CalendarTokenEncryptor` | `CalendarOAuthService` |
| `tenant_ticket_integrations` | `encrypted_api_token` | `TEXT NOT NULL` | `TicketIntegrationTokenEncryptor` | `TicketIntegrationConfigService` |
| `inbound_webhook_signing_secrets` | `encrypted_secret` | `TEXT NOT NULL` | `TicketIntegrationTokenEncryptor` (reused — see below) | `WebhookSigningSecretService` |
| `users` | `totp_secret` | `VARCHAR(512)` (nullable) | `TotpSecretEncryptor` | `TotpService` |

One thing worth flagging that the Phase 3 audit's "four encryptors" framing
undersells: `WebhookSigningSecretService` (inbound alert-source webhook signing
secrets — GitHub/PagerDuty/Grafana) doesn't have its own encryptor, it **reuses**
`TicketIntegrationTokenEncryptor` directly (see its class comment: *"Reuses
TicketIntegrationTokenEncryptor (AES/GCM, app.integrations.token-encryption-key)"*).
So the real blast radius is **5 columns across 5 tables sharing 4 keys**, not
4-and-4. This actually simplifies the fix slightly: fixing
`TicketIntegrationTokenEncryptor` for versioning automatically covers
`inbound_webhook_signing_secrets` too, with no fifth class needed.

`totp_secret` is nullable/no `NOT NULL`, everything else is `NOT NULL TEXT`.

## 4. Any real rows anywhere, or all per-run test fixtures?

No evidence of real persisted rows in this repo:

- No Flyway seed/data migration inserts any value into these five columns —
  I grepped all `db/migration/*.sql` for the column names; every hit is a
  `CREATE TABLE`/`ALTER TABLE` DDL statement, never an `INSERT`.
- No test fixture files with static ciphertext — the encryptor unit tests
  (`SlackTokenEncryptorTest`, etc.) and the service-level tests construct a
  fresh `SecretKeySpec` from a fixed test key and round-trip encrypt/decrypt
  in-memory per test run; nothing is checked into the repo as a pre-encrypted
  value.
- `src/test/resources/application-test.yml` supplies a single fixed key per
  purpose for the test profile (`aetherops-totp-encryption-key-01`,
  `aetherops-slack-token-enc-key-01`, etc.) — consistent with the memory note
  that the test profile bypasses/stubs security concerns rather than exercising
  production key material.

Caveat I can't resolve from static analysis alone: `railway.toml` has an
uncommitted change in your working tree (`healthcheckPath` →
`/actuator/health/liveness`), which tells me there's a live Railway deployment
target configured, but I have no way to inspect what rows actually exist in
that deployed database from here. Everything in-repo is consistent with your
framing ("only test data so far"), but if a Railway (or other) environment has
already been used to click through the Slack/calendar/Jira OAuth flows or
enroll a real TOTP user, those rows would be real production-shaped data sitting
under the old unversioned format today. Worth a 10-second confirmation from you
before I treat "no migration needed" as settled — I'm treating it as **very
likely true, not certain**.

Assuming it's confirmed clean: **no data migration is needed**, only a
format/code change, exactly as you said.

## 5. Design proposal

### Format

```
Base64( keyId(1 byte, unsigned) || IV(12 bytes) || GCM-output(ciphertext || 16-byte tag) )
```

- `keyId` is a single unsigned byte (0–255) — plenty for any realistic rotation
  schedule (you rotate keys, not accumulate hundreds of live versions).
- Reserve `keyId == 0` to *mean* "this is actually the legacy unversioned
  format, not a real version 0" — see below, this makes the fallback path an
  explicit, named case rather than an implicit "parsing failed, assume old."
- Everything after the keyId byte is unchanged from today's layout (IV then
  GCM output), so the crypto core doesn't change at all, only what gets
  concatenated before Base64-encoding.

### Can the old and new formats be told apart reliably?

**Not from byte layout alone — but reliably in practice via the GCM auth tag.**
I want to be precise about this because it's the crux of whether a live-data
migration is mandatory:

- There is no magic number, no length invariant, and no structural marker that
  distinguishes "IV || ciphertext" from "keyId || IV || ciphertext" — both are
  just opaque byte strings that happen to be 1 byte apart in length for the
  same plaintext. A legacy blob's first byte is a random IV byte; it can by
  chance equal a valid configured keyId.
- However, GCM's 128-bit authentication tag makes attempting the wrong parse
  effectively self-correcting: if you tentatively treat byte 0 as a keyId,
  look up that key, and try to GCM-decrypt bytes `[1..]` as `IV || ciphertext`,
  decryption will only *succeed* if that really was the right key **and** the
  right framing — GCM verifies the tag before returning plaintext, and a
  1-byte framing shift changes every byte of the "IV" and "ciphertext" the
  cipher sees, so a false-accept has probability ~2⁻¹²⁸ (astronomically
  negligible, not a real risk).
- So the reliable decode algorithm is: **try new-format parse first (only when
  byte 0 matches a currently-configured non-legacy keyId); if that GCM-decrypts
  successfully, accept it as versioned; otherwise fall back to the legacy parse
  (byte 0 is part of the IV, decrypt the whole blob with the designated legacy
  key)**. This is a "trial decrypt," not a static tag check — worth being
  upfront about, since it's a slightly different shape than a typical
  self-describing format, but it is deterministic and safe given AEAD.
- Net answer to your question: distinguishing is reliable, so **this does not
  force a mandatory eager migration** — old and new ciphertexts can coexist
  indefinitely and decrypt correctly either way. (A background re-encryption
  job to eventually retire the legacy path is still a good idea once rotation
  actually happens, but it's optional cleanup, not a correctness requirement.)

### Config shape

Per purpose, move from one scalar key to a keyed map plus a current pointer,
e.g. for Slack:

```yaml
app:
  slack:
    token-encryption:
      current: 1
      keys:
        0: ${APP_SLACK_TOKEN_ENCRYPTION_KEY:}      # legacy key, same value as today — required so old rows keep decrypting
        1: ${APP_SLACK_TOKEN_ENCRYPTION_KEY_V1:}    # first "real" versioned key
```

On day one of deploying this change, `keys[0]` and `keys[1]` would point at the
*same* existing secret (no key value changes on this deploy — this step is
purely about the envelope format). A future rotation adds `keys[2]`, points
`current` at 2, and leaves `keys[0]`/`keys[1]` in place until every row
encrypted under them has been re-encrypted (or forever, if you're fine keeping
old keys around — cheaper and the norm for this kind of rotation).

Same shape repeats for `app.calendar.token-encryption`,
`app.integrations.token-encryption`, and `app.totp.encryption`.

### Shared base class

Extract one class, e.g. `com.aetherops.crypto.VersionedAesGcmEncryptor`, owning:
- the `ALGORITHM`/`GCM_IV_LENGTH`/`GCM_TAG_LENGTH` constants (defined once),
- a constructor taking `Map<Integer, SecretKeySpec> keysByVersion` + `int currentVersion`,
- `encrypt(String)`: prepend `currentVersion` as a single byte, IV, GCM-output, Base64.
- `decrypt(String)`: the try-new-then-fall-back-to-legacy algorithm above.

Each of the four (really: four call sites, since the ticket/webhook one is
shared) becomes a thin `@Component` that builds the key map from its own
`@Value`-injected properties in its constructor and delegates to the shared
base class — same pattern as today (one class per purpose, for clean DI and
distinct config namespaces / error messages), but with zero duplicated crypto
logic.

### What I have NOT yet done

No code changes, no config changes, nothing committed — this file is the only
write. Waiting for your go-ahead (and confirmation on the "no real rows
anywhere" question in §4) before implementing.

---

## 6. Implementation (design approved, now built)

Confirmed before implementing: no real rows anywhere (Railway account suspended,
nothing deployed, the uncommitted `railway.toml` edit is prep for a first
deployment that hasn't happened) — so this was a pure format/code change, no
data migration.

### Can webhook signing secrets get their own key version space?

**No, not without extra complexity — and "extra complexity" here actually
means splitting the trust domain, which you said to defer.** The version
space is a property of the *key map* (`Map<Integer, SecretKeySpec>`), not of
the call site. `WebhookSigningSecretService` and `TicketIntegrationConfigService`
share one `TicketIntegrationTokenEncryptor` instance, which means they share
one key map and one `current` pointer — there is no second axis to hang an
independent version number on unless the two get independent key material
(i.e. a real split into two encryptors/two env-var namespaces), which is the
change you explicitly said not to make this pass. Numbering the two domains'
versions differently while both numbers resolve to the same underlying keys
would add bookkeeping for zero cryptographic benefit — a compromised key
compromises both regardless of the label. So instead of a fake independent
version space, I documented the coupling explicitly, prominently, in both
places that share it:
- `TicketIntegrationTokenEncryptionProperties` class Javadoc
- `WebhookSigningSecretService` class Javadoc (new "Trust-domain coupling this
  creates" section)

Both point at this report so the next person who considers splitting them
knows why they weren't already split.

### Files changed

**New:**
- `src/main/java/com/aetherops/crypto/VersionedAesGcmEncryptor.java` — the
  shared core: wire format, encrypt/decrypt, the trial-decrypt-then-fall-back
  algorithm, `buildKeySpec` validation.
- `src/main/java/com/aetherops/slack/SlackTokenEncryptionProperties.java`
- `src/main/java/com/aetherops/calendar/CalendarTokenEncryptionProperties.java`
- `src/main/java/com/aetherops/integrations/service/TicketIntegrationTokenEncryptionProperties.java`
- `src/main/java/com/aetherops/auth/service/TotpEncryptionProperties.java`
  (each a small `@Configuration @ConfigurationProperties` holder — same
  pattern as the existing `TwilioConfig`/`StripeConfig` — for `current` +
  `keys` per purpose)
- `src/test/java/com/aetherops/crypto/VersionedAesGcmEncryptorTest.java`
- `src/test/java/com/aetherops/slack/SlackTokenEncryptionPropertiesTest.java`
  (verifies Spring's relaxed binding actually produces the `Map<Integer,String>`
  the encryptors rely on — the one part a plain `new XEncryptor(...)` unit
  test can't exercise, since that bypasses property binding entirely)

**Changed:**
- `SlackTokenEncryptor` / `CalendarTokenEncryptor` / `TicketIntegrationTokenEncryptor`
  / `TotpSecretEncryptor` — now thin wrappers delegating to
  `VersionedAesGcmEncryptor`, constructed from their new `*Properties` class.
  Public `encrypt(String)`/`decrypt(String)` API unchanged, so every caller
  (`SlackOAuthController`, `CalendarOAuthService`, `TicketIntegrationConfigService`,
  `TotpService`, `WebhookSigningSecretService`) needed no changes.
- `WebhookSigningSecretService` — Javadoc only, documenting the coupling above.
- `application.yml` — comments updated to the new env var names
  (`APP_*_TOKEN_ENCRYPTION_CURRENT`, `APP_*_TOKEN_ENCRYPTION_KEYS_n`); still no
  secret values committed.
- `application-test.yml`, `AbstractIntegrationTest` — updated to the new
  nested `current`/`keys` shape (values unchanged, still test-only keys).
- The four existing encryptor unit tests — updated to build a small
  `*Properties` object instead of passing a bare string, same test intent
  (round-trip, distinct ciphertexts each call, reject-wrong-length,
  reject-missing-key) preserved.

**Not touched (flagged, not fixed — out of scope for this step):**
`kubernetes/deployment.yaml`/`kubernetes/secret.yaml` still reference the old
`app.totp.encryption-key` shape via env var `APP_TOTP_ENCRYPTIONKEY` (note:
that's already missing an underscore vs. the property path even today, so it
likely never worked). Railway, not Kubernetes, is the actual deployment
target per your confirmation above, so I left these manifests alone rather
than fix an unrelated pre-existing bug in an unused path — worth a cleanup
pass if Kubernetes is ever actually used.

### New tests, by name

- `VersionedAesGcmEncryptorTest.legacyUnversionedCiphertext_stillDecryptsAfterVersioningIsIntroduced`
  — builds a ciphertext with the exact pre-change algorithm (no version byte,
  reproduced independently in the test, not by calling production code) and
  confirms it still decrypts once versioning is in place.
- `VersionedAesGcmEncryptorTest.rotation_oldCiphertextStillDecrypts_andNewCiphertextUsesNewVersion`
  — the rotation proof you asked for: encrypts under key v1 (current), then
  constructs a second encryptor representing post-rotation state (v1 + v2,
  v2 current), confirms it decrypts the v1 ciphertext AND that a fresh
  encryption is tagged with v2 and round-trips.
- `VersionedAesGcmEncryptorTest.encryptDecrypt_roundTrip_returnsOriginal`
- `VersionedAesGcmEncryptorTest.encrypt_taggedWithCurrentVersion`
- `VersionedAesGcmEncryptorTest.constructor_rejectsCurrentVersionEqualToReservedLegacyVersion`
- `VersionedAesGcmEncryptorTest.constructor_rejectsCurrentVersionWithNoConfiguredKey`
- `VersionedAesGcmEncryptorTest.buildKeySpec_rejectsKeyOfWrongLength`
- `SlackTokenEncryptionPropertiesTest.bindsCurrentAndIndexedKeysFromEnvVarStyleProperties`
- `SlackTokenEncryptionPropertiesTest.defaultsCurrentToOneAndKeysToEmptyWhenUnconfigured`

### Test run

Docker is not running locally (confirmed via `docker info`), so the
Testcontainers-backed integration suite (`AbstractIntegrationTest` and
everything extending it, including `TotpIntegrationTest`) cannot execute in
this environment — every one of those fails at container startup with
`IllegalStateException: Could not find a valid Docker environment`, not from
anything in this change (verified by inspecting the stack traces).

You said "push to CI" as the fallback for that case, but this task also says
"do not commit" (both at the start and again just now) — pushing to CI
requires a commit, so I did not do that; flagging the conflict rather than
guessing which instruction wins. Instead, ran everything runnable locally:

- Targeted: `mvn test -Dtest=VersionedAesGcmEncryptorTest,SlackTokenEncryptorTest,CalendarTokenEncryptorTest,TicketIntegrationTokenEncryptorTest,TotpSecretEncryptorTest,SlackTokenEncryptionPropertiesTest`
  → **23 tests, 0 failures, 0 errors.**
- `mvn test-compile` (full main + test source tree) → clean, confirming every
  other file that references these four encryptor classes (mock-based service
  tests: `SlackConversationServiceTest`, `CalendarOAuthServiceTest`,
  `TicketIntegrationConfigServiceTest`, `JiraTicketServiceTest`,
  `ServiceNowTicketServiceTest`, `WebhookSigningSecretServiceTest`, etc.) still
  compiles against the new constructors.
- Full `mvn test -Dmaven.test.failure.ignore=true` (whole suite): **1059 run,
  0 failures, 290 errors** — every one of the 290 errors is the same Docker
  unavailability (`NoClassDefFoundError: Could not initialize class
  com.aetherops.AbstractIntegrationTest` ← `Could not find a valid Docker
  environment`), confirmed by inspecting multiple surefire reports including
  `TotpIntegrationTest`'s. **Every non-Docker test in the repo passes; zero
  failures anywhere.**

Not verified in this session: `TotpIntegrationTest` and the other
`AbstractIntegrationTest`-based suites actually booting the full Spring
context with real `TotpEncryptionProperties`/etc. beans wired end-to-end
(needs Docker). `SlackTokenEncryptionPropertiesTest` covers the specific
binding mechanism those beans depend on in isolation, which is the part most
likely to break silently, but it isn't a substitute for the real integration
run. Let me know if you'd like me to push to a branch for CI despite the
no-commit instruction, or wait until Docker/CI is available to close that
gap.
