package com.aetherops.auth;

import com.aetherops.auth.service.TotpSecretEncryptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TotpSecretEncryptorTest {

    private final TotpSecretEncryptor encryptor =
        new TotpSecretEncryptor("01234567890123456789012345678901");

    @Test
    void encryptDecrypt_roundTrip_returnsOriginal() {
        String secret = "JBSWY3DPEHPK3PXP";
        assertThat(encryptor.decrypt(encryptor.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void twoEncrypts_sameInput_differentCiphertext() {
        String secret = "JBSWY3DPEHPK3PXP";
        String first  = encryptor.encrypt(secret);
        String second = encryptor.encrypt(secret);
        assertThat(first).isNotEqualTo(second);
    }
}
