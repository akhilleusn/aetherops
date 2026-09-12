package com.aetherops.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * Shared AES/GCM core for every "encrypt a secret before storing it" use case in this codebase
 * (Slack bot tokens, calendar refresh tokens, ticket-integration API tokens / inbound webhook
 * signing secrets, TOTP secrets). Closes Phase 3 finding 1 (see STEP_REPORT.md): the four
 * call sites used to duplicate this logic byte-for-byte with no key identifier stored alongside
 * the ciphertext, so rotating a key made every value it had ever encrypted permanently
 * undecryptable.
 *
 * <p><b>Wire format</b> (all versioned ciphertext produced by {@link #encrypt}):
 * <pre>
 * Base64( keyVersion(1 byte, unsigned) || IV(12 bytes) || GCM-output(ciphertext || 16-byte tag) )
 * </pre>
 * {@code keyVersion 0} is reserved to mean "this is actually the pre-versioning legacy format,
 * not a real version 0" — see {@link #decrypt} for how that's told apart from a real version.
 *
 * <p><b>Legacy format</b> (everything ever written before this change): {@code Base64(IV(12
 * bytes) || GCM-output)}, one byte shorter, with no version prefix at all and no way to tell
 * from byte layout alone which key produced it. {@link #decrypt} keeps accepting it forever
 * (or until every legacy row has been re-encrypted) via {@code keysByVersion.get(0)}.
 *
 * <p><b>Why trial-decrypt is safe here:</b> there is no structural marker distinguishing the two
 * formats — a legacy blob's first byte is just a random IV byte, which can by chance equal a
 * configured key version. {@link #decrypt} resolves this by attempting the versioned parse
 * first and trusting GCM's 128-bit authentication tag to reject it: decryption only succeeds if
 * the guessed key version and framing were both actually correct, since a wrong key or a
 * 1-byte framing shift changes every byte the cipher sees and the tag check fails first. A
 * false accept has probability on the order of 2^-128 — not a real risk. If the versioned
 * attempt fails for any reason (unknown version byte, or tag mismatch), this falls back to the
 * legacy parse unconditionally.
 */
public final class VersionedAesGcmEncryptor {

    /** Reserved key version identifying the pre-versioning, unprefixed wire format. */
    public static final int LEGACY_VERSION = 0;

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final Map<Integer, SecretKeySpec> keysByVersion;
    private final int currentVersion;
    private final String purpose;

    /**
     * @param keysByVersion every key this encryptor must be able to decrypt with, including
     *                      (at minimum) an entry for {@code currentVersion} and, unless this is
     *                      the very first key this purpose has ever had, an entry for
     *                      {@link #LEGACY_VERSION} so pre-existing unversioned ciphertext keeps
     *                      decrypting.
     * @param currentVersion the key version {@link #encrypt} tags new ciphertext with; must not
     *                       be {@link #LEGACY_VERSION} and must be present in {@code keysByVersion}.
     * @param purpose        short human-readable label used only in exception messages (e.g.
     *                       "Slack bot token").
     */
    public VersionedAesGcmEncryptor(Map<Integer, SecretKeySpec> keysByVersion, int currentVersion, String purpose) {
        if (currentVersion == LEGACY_VERSION) {
            throw new IllegalArgumentException(
                "currentVersion must not be the reserved legacy version " + LEGACY_VERSION + " (" + purpose + ")");
        }
        if (!keysByVersion.containsKey(currentVersion)) {
            throw new IllegalArgumentException(
                "no key configured for currentVersion " + currentVersion + " (" + purpose + ")");
        }
        this.keysByVersion = Map.copyOf(keysByVersion);
        this.currentVersion = currentVersion;
        this.purpose = purpose;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            byte[] ciphertext = runCipher(
                Cipher.ENCRYPT_MODE, keysByVersion.get(currentVersion), iv, plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[1 + GCM_IV_LENGTH + ciphertext.length];
            combined[0] = (byte) currentVersion;
            System.arraycopy(iv, 0, combined, 1, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, 1 + GCM_IV_LENGTH, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt " + purpose, e);
        }
    }

    public String decrypt(String encoded) {
        byte[] combined = Base64.getDecoder().decode(encoded);

        if (combined.length > 1 + GCM_IV_LENGTH) {
            int candidateVersion = combined[0] & 0xFF;
            SecretKeySpec candidateKey = candidateVersion == LEGACY_VERSION ? null : keysByVersion.get(candidateVersion);
            if (candidateKey != null) {
                try {
                    byte[] iv = Arrays.copyOfRange(combined, 1, 1 + GCM_IV_LENGTH);
                    byte[] ciphertext = Arrays.copyOfRange(combined, 1 + GCM_IV_LENGTH, combined.length);
                    byte[] plaintext = runCipher(Cipher.DECRYPT_MODE, candidateKey, iv, ciphertext);
                    return new String(plaintext, StandardCharsets.UTF_8);
                } catch (GeneralSecurityException notVersionedOrWrongKey) {
                    // First byte happened to look like a configured version but the GCM tag
                    // didn't verify -- this wasn't actually versioned ciphertext under that key.
                    // Fall through to the legacy parse below.
                }
            }
        }

        SecretKeySpec legacyKey = keysByVersion.get(LEGACY_VERSION);
        if (legacyKey == null) {
            throw new RuntimeException(
                "Failed to decrypt " + purpose + ": not a recognized versioned ciphertext and no legacy key is configured");
        }
        try {
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            byte[] plaintext = runCipher(Cipher.DECRYPT_MODE, legacyKey, iv, ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt " + purpose, e);
        }
    }

    private static byte[] runCipher(int mode, SecretKeySpec key, byte[] iv, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return cipher.doFinal(input);
    }

    /**
     * Builds the AES-256 key for one configured key version, validating length the same way
     * every purpose-specific encryptor did before this class existed.
     */
    public static SecretKeySpec buildKeySpec(String key, int version, String purpose) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                purpose + " key version " + version + " must be exactly 32 characters (AES-256).");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
