package com.aetherops.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionedAesGcmEncryptorTest {

    private static final String PURPOSE = "test secret";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private static SecretKeySpec key(String raw) {
        return VersionedAesGcmEncryptor.buildKeySpec(raw, 1, PURPOSE);
    }

    private static int versionByteOf(String encoded) {
        return Base64.getDecoder().decode(encoded)[0] & 0xFF;
    }

    /** Reproduces the pre-versioning wire format: Base64(IV || GCM-output), no version byte. */
    private static String encryptLegacyFormat(SecretKeySpec keySpec, String plaintext) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[GCM_IV_LENGTH + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
        System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    @Test
    void encryptDecrypt_roundTrip_returnsOriginal() {
        VersionedAesGcmEncryptor encryptor =
            new VersionedAesGcmEncryptor(Map.of(1, key("01234567890123456789012345678901")), 1, PURPOSE);
        assertThat(encryptor.decrypt(encryptor.encrypt("hello world"))).isEqualTo("hello world");
    }

    @Test
    void encrypt_taggedWithCurrentVersion() {
        VersionedAesGcmEncryptor encryptor =
            new VersionedAesGcmEncryptor(Map.of(1, key("01234567890123456789012345678901")), 1, PURPOSE);
        assertThat(versionByteOf(encryptor.encrypt("hello world"))).isEqualTo(1);
    }

    /**
     * Proves a value encrypted before this change (no version prefix at all) still decrypts
     * correctly once the versioned format is introduced -- this is what stops deploying this
     * change from breaking every existing Slack token / calendar refresh token / ticket
     * integration token / TOTP secret / webhook signing secret.
     */
    @Test
    void legacyUnversionedCiphertext_stillDecryptsAfterVersioningIsIntroduced() throws Exception {
        SecretKeySpec legacyKey = key("01234567890123456789012345678901");
        String legacyCiphertext = encryptLegacyFormat(legacyKey, "pre-existing-secret-value");

        // State right after this change deploys: no rotation has happened yet, so version 0
        // (legacy) and version 1 (current) are backed by the same key -- exactly what
        // SlackTokenEncryptor et al. do via keys.putIfAbsent(LEGACY_VERSION, ...).
        VersionedAesGcmEncryptor encryptor = new VersionedAesGcmEncryptor(
            Map.of(VersionedAesGcmEncryptor.LEGACY_VERSION, legacyKey, 1, legacyKey), 1, PURPOSE);

        assertThat(encryptor.decrypt(legacyCiphertext)).isEqualTo("pre-existing-secret-value");
    }

    /**
     * The actual rotation capability this whole change exists for: a value encrypted under the
     * old current key must keep decrypting after a new key becomes current, and new encryptions
     * must be tagged with the new version -- not just "the format parses."
     */
    @Test
    void rotation_oldCiphertextStillDecrypts_andNewCiphertextUsesNewVersion() {
        SecretKeySpec keyV1 = key("01234567890123456789012345678901");
        SecretKeySpec keyV2 = key("abcdefghijklmnopqrstuvwxyz012345");

        VersionedAesGcmEncryptor beforeRotation = new VersionedAesGcmEncryptor(Map.of(1, keyV1), 1, PURPOSE);
        String ciphertextUnderV1 = beforeRotation.encrypt("pre-rotation-secret");
        assertThat(versionByteOf(ciphertextUnderV1)).isEqualTo(1);

        // Rotation: v2 becomes current; v1 stays configured so old ciphertext keeps decrypting.
        VersionedAesGcmEncryptor afterRotation = new VersionedAesGcmEncryptor(Map.of(1, keyV1, 2, keyV2), 2, PURPOSE);

        assertThat(afterRotation.decrypt(ciphertextUnderV1)).isEqualTo("pre-rotation-secret");

        String ciphertextUnderV2 = afterRotation.encrypt("post-rotation-secret");
        assertThat(versionByteOf(ciphertextUnderV2)).isEqualTo(2);
        assertThat(afterRotation.decrypt(ciphertextUnderV2)).isEqualTo("post-rotation-secret");
    }

    @Test
    void constructor_rejectsCurrentVersionEqualToReservedLegacyVersion() {
        assertThrows(IllegalArgumentException.class, () -> new VersionedAesGcmEncryptor(
            Map.of(0, key("01234567890123456789012345678901")), 0, PURPOSE));
    }

    @Test
    void constructor_rejectsCurrentVersionWithNoConfiguredKey() {
        assertThrows(IllegalArgumentException.class, () -> new VersionedAesGcmEncryptor(
            Map.of(1, key("01234567890123456789012345678901")), 2, PURPOSE));
    }

    @Test
    void buildKeySpec_rejectsKeyOfWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> VersionedAesGcmEncryptor.buildKeySpec("too-short", 1, PURPOSE));
    }
}
