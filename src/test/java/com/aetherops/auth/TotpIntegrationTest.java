package com.aetherops.auth;

import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.TotpBackupCode;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.TotpBackupCodeRepository;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.auth.service.JwtService;
import com.aetherops.auth.service.TotpSecretEncryptor;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.aetherops.AbstractIntegrationTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class TotpIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired TotpBackupCodeRepository backupCodeRepository;
    @Autowired TotpSecretEncryptor encryptor;
    @Autowired JwtService jwtService;

    private UUID tenantId;
    private UUID userId;
    private User user;

    private static final String RAW_SECRET = "JBSWY3DPEHPK3PXP";
    private static final String PASSWORD    = "password123";

    @BeforeEach
    void setUp() {
        backupCodeRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        Tenant tenant = tenantRepository.save(Tenant.builder()
            .name("Test Corp")
            .plan(Plan.STARTER)
            .aiMonthlyBudgetCents(0)
            .retentionDays(30)
            .build());
        tenantId = tenant.getId();

        user = userRepository.save(User.builder()
            .email("totp-test@example.com")
            .passwordHash(new BCryptPasswordEncoder().encode(PASSWORD))
            .role(Role.MEMBER)
            .tenantId(tenantId)
            .emailVerified(true)
            .build());
        userId = user.getId();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private String fullJwt() {
        return jwtService.generateToken(user);
    }

    private String partialJwt() {
        return jwtService.generatePartialToken(user);
    }

    private void enable2FA() {
        user.setTotpSecret(encryptor.encrypt(RAW_SECRET));
        user.setTwoFaEnabled(true);
        user = userRepository.save(user);
    }

    private void setup2FANotYetEnabled() {
        user.setTotpSecret(encryptor.encrypt(RAW_SECRET));
        user.setTwoFaEnabled(false);
        user = userRepository.save(user);
    }

    private String currentTotpCode() throws Exception {
        DefaultCodeGenerator gen = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        long period = new SystemTimeProvider().getTime() / 30;
        return gen.generate(RAW_SECRET, period);
    }

    private void addBackupCode(String plainCode) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(plainCode.getBytes(StandardCharsets.UTF_8));
            backupCodeRepository.save(TotpBackupCode.builder()
                .userId(userId)
                .codeHash(HexFormat.of().formatHex(hash))
                .used(false)
                .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── setup ─────────────────────────────────────────────────────────────

    @Test
    void setup_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/2fa/setup")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void setup_authenticated_returnsQrAndBackupCodes() throws Exception {
        mockMvc.perform(post("/api/v1/auth/2fa/setup")
                .header("Authorization", "Bearer " + fullJwt())
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.qrCodeUrl").value(org.hamcrest.Matchers.startsWith("otpauth://totp/")))
            .andExpect(jsonPath("$.data.secret").isNotEmpty())
            .andExpect(jsonPath("$.data.backupCodes").isArray())
            .andExpect(jsonPath("$.data.backupCodes.length()").value(8));
    }

    @Test
    void verifySetup_correctCode_enablesTwoFa() throws Exception {
        setup2FANotYetEnabled();

        mockMvc.perform(post("/api/v1/auth/2fa/verify-setup")
                .header("Authorization", "Bearer " + fullJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", currentTotpCode()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        assertThat(userRepository.findById(userId).orElseThrow().isTwoFaEnabled()).isTrue();
    }

    @Test
    void verifySetup_wrongCode_returns400() throws Exception {
        setup2FANotYetEnabled();

        mockMvc.perform(post("/api/v1/auth/2fa/verify-setup")
                .header("Authorization", "Bearer " + fullJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", "000000"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void verifySetup_alreadyEnabled_returns409() throws Exception {
        enable2FA();

        mockMvc.perform(post("/api/v1/auth/2fa/verify-setup")
                .header("Authorization", "Bearer " + fullJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", currentTotpCode()))))
            .andExpect(status().isConflict());
    }

    // ── login flow ────────────────────────────────────────────────────────

    @Test
    void login_twoFaDisabled_returnsFullToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("email", "totp-test@example.com", "password", PASSWORD))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.requiresTwoFactor").doesNotExist());
    }

    @Test
    void login_twoFaEnabled_returnsPartialToken() throws Exception {
        enable2FA();

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("email", "totp-test@example.com", "password", PASSWORD))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.requiresTwoFactor").value(true))
            .andExpect(jsonPath("$.data.partialToken").isNotEmpty())
            .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    void verify_validTotpCode_returnsFullJwt() throws Exception {
        enable2FA();

        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("partialToken", partialJwt(), "code", currentTotpCode()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.email").value("totp-test@example.com"));
    }

    @Test
    void verify_invalidCode_returns400() throws Exception {
        enable2FA();

        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("partialToken", partialJwt(), "code", "000000"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void verify_invalidPartialToken_returns400() throws Exception {
        enable2FA();

        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("partialToken", "not.a.valid.jwt", "code", currentTotpCode()))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void verify_fullTokenAsPartialToken_returns400() throws Exception {
        enable2FA();

        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("partialToken", fullJwt(), "code", currentTotpCode()))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void verify_validBackupCode_returnsFullJwt() throws Exception {
        enable2FA();
        addBackupCode("TESTCODE");

        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("partialToken", partialJwt(), "code", "TESTCODE"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void verify_usedBackupCode_returns400() throws Exception {
        enable2FA();
        addBackupCode("TESTCODE");

        // First use — succeeds
        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("partialToken", partialJwt(), "code", "TESTCODE"))))
            .andExpect(status().isOk());

        // Second use — rejected
        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("partialToken", partialJwt(), "code", "TESTCODE"))))
            .andExpect(status().isBadRequest());
    }

    // ── Phase 1 Step C, item 2: per-partial-token brute-force lockout ─────

    @Test
    void verify_5FailedAttempts_locksSessionEvenWithCorrectCode() throws Exception {
        enable2FA();
        String partialToken = partialJwt();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/2fa/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        Map.of("partialToken", partialToken, "code", "000000"))))
                .andExpect(status().isBadRequest());
        }

        // The session is now locked — even the CORRECT code is rejected. Recovery is a fresh
        // login (a new partial token), not retrying this one.
        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("partialToken", partialToken, "code", currentTotpCode()))))
            .andExpect(status().isLocked());
    }

    @Test
    void verify_lockedSession_freshLoginGetsFreshPartialTokenThatWorks() throws Exception {
        enable2FA();
        String lockedToken = partialJwt();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/2fa/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        Map.of("partialToken", lockedToken, "code", "000000"))))
                .andExpect(status().isBadRequest());
        }

        // A brand-new partial token (as if the user logged in again) is a different counter —
        // this proves the lock is per-SESSION, not per-account. JWT "iat"/"exp" are NumericDate
        // (whole seconds, per RFC 7519), so two tokens minted within the same second for the
        // same user are byte-identical; sleep past the second boundary so this one is genuinely
        // a different token, not an accidental duplicate of lockedToken.
        Thread.sleep(1100);
        String freshToken = partialJwt();
        mockMvc.perform(post("/api/v1/auth/2fa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("partialToken", freshToken, "code", currentTotpCode()))))
            .andExpect(status().isOk());
    }

    // ── partial token cannot access protected endpoints ───────────────────

    @Test
    void partialToken_cannotAccessProtectedEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/auth/2fa/setup")
                .header("Authorization", "Bearer " + partialJwt())
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    // ── disable ───────────────────────────────────────────────────────────

    @Test
    void disable_correctPassword_clearsTwoFa() throws Exception {
        enable2FA();

        mockMvc.perform(post("/api/v1/auth/2fa/disable")
                .header("Authorization", "Bearer " + fullJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("password", PASSWORD))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        User updated = userRepository.findById(userId).orElseThrow();
        assertThat(updated.isTwoFaEnabled()).isFalse();
        assertThat(updated.getTotpSecret()).isNull();
    }

    @Test
    void disable_wrongPassword_returns400() throws Exception {
        enable2FA();

        mockMvc.perform(post("/api/v1/auth/2fa/disable")
                .header("Authorization", "Bearer " + fullJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("password", "wrongpassword"))))
            .andExpect(status().isUnauthorized());
    }
}
