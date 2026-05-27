package com.aetherops.auth;

import com.aetherops.auth.model.PasswordResetToken;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.PasswordResetTokenRepository;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.auth.service.EmailService;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired PasswordResetTokenRepository tokenRepository;
    @SpyBean EmailService emailService;

    private UUID tenantId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        Tenant tenant = tenantRepository.save(Tenant.builder()
            .name("Test Corp")
            .plan(Plan.STARTER)
            .aiMonthlyBudgetCents(0)
            .retentionDays(30)
            .build());
        tenantId = tenant.getId();

        User user = userRepository.save(User.builder()
            .email("alice@example.com")
            .passwordHash(new BCryptPasswordEncoder().encode("password123"))
            .role(Role.MEMBER)
            .tenantId(tenantId)
            .build());
        userId = user.getId();

        Mockito.clearInvocations(emailService);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Insert a valid (non-expired, not used) token directly into the DB. */
    private String insertValidToken() {
        String rawToken = UUID.randomUUID().toString();
        tokenRepository.save(PasswordResetToken.builder()
            .userId(userId)
            .tokenHash(sha256(rawToken))
            .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
            .used(false)
            .build());
        return rawToken;
    }

    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // ── forgot-password ───────────────────────────────────────────────────

    @Test
    void forgotPassword_knownEmail_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "alice@example.com"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void forgotPassword_unknownEmail_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "nobody@example.com"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void forgotPassword_unknownEmail_doesNotSendEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "nobody@example.com"))))
            .andExpect(status().isOk());

        verify(emailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    void forgotPassword_createsHashedTokenInDB() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "alice@example.com"))))
            .andExpect(status().isOk());

        assertThat(tokenRepository.count()).isEqualTo(1);
        PasswordResetToken token = tokenRepository.findAll().get(0);
        assertThat(token.getUserId()).isEqualTo(userId);
        assertThat(token.isUsed()).isFalse();
        assertThat(token.getExpiresAt()).isAfter(Instant.now());
        assertThat(token.getTokenHash()).hasSize(64);
    }

    @Test
    void forgotPassword_invalidEmailFormat_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "not-an-email"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.details.email").isNotEmpty());
    }

    // ── reset-password ────────────────────────────────────────────────────

    @Test
    void resetPassword_validToken_returns200() throws Exception {
        String rawToken = insertValidToken();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("token", rawToken, "newPassword", "newpassword123"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void resetPassword_enablesLoginWithNewPassword() throws Exception {
        String rawToken = insertValidToken();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("token", rawToken, "newPassword", "newpassword123"))))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("email", "alice@example.com", "password", "newpassword123"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void resetPassword_invalidToken_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("token", UUID.randomUUID().toString(), "newPassword", "newpassword123"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Invalid or expired password reset token."));
    }

    @Test
    void resetPassword_usedToken_returns400() throws Exception {
        String rawToken = insertValidToken();

        // First use — success
        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("token", rawToken, "newPassword", "newpassword123"))))
            .andExpect(status().isOk());

        // Second use — already used
        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("token", rawToken, "newPassword", "anotherpassword"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void resetPassword_expiredToken_returns400() throws Exception {
        String rawToken = UUID.randomUUID().toString();
        tokenRepository.save(PasswordResetToken.builder()
            .userId(userId)
            .tokenHash(sha256(rawToken))
            .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .used(false)
            .build());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("token", rawToken, "newPassword", "newpassword123"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void resetPassword_shortPassword_returns422() throws Exception {
        String rawToken = insertValidToken();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("token", rawToken, "newPassword", "abc"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.details.newPassword").isNotEmpty());
    }
}
