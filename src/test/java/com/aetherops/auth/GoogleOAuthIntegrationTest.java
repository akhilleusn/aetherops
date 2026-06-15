package com.aetherops.auth;

import com.aetherops.auth.model.GoogleUserInfo;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.auth.service.GoogleTokenVerifier;
import com.aetherops.common.exception.GoogleAuthNotConfiguredException;
import com.aetherops.common.exception.InvalidGoogleTokenException;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.aetherops.AbstractIntegrationTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoogleOAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @MockitoBean  GoogleTokenVerifier googleTokenVerifier;

    private UUID tenantId;
    private static final String FAKE_ID_TOKEN = "fake.google.id.token";
    private static final String GOOGLE_SUB    = "google-sub-12345";
    private static final String USER_EMAIL    = "google-user@example.com";

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        Tenant tenant = tenantRepository.save(Tenant.builder()
            .name("Test Corp")
            .plan(Plan.STARTER)
            .aiMonthlyBudgetCents(0)
            .retentionDays(30)
            .build());
        tenantId = tenant.getId();

        when(googleTokenVerifier.isConfigured()).thenReturn(true);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private User saveExistingUser() {
        return userRepository.save(User.builder()
            .email(USER_EMAIL)
            .passwordHash(new BCryptPasswordEncoder().encode("password123"))
            .googleSub(GOOGLE_SUB)
            .role(Role.MEMBER)
            .tenantId(tenantId)
            .build());
    }

    private User saveUserWithoutGoogleSub() {
        return userRepository.save(User.builder()
            .email(USER_EMAIL)
            .passwordHash(new BCryptPasswordEncoder().encode("password123"))
            .role(Role.MEMBER)
            .tenantId(tenantId)
            .build());
    }

    private String googleBody(UUID tenantOverride) throws Exception {
        if (tenantOverride != null) {
            return objectMapper.writeValueAsString(
                Map.of("idToken", FAKE_ID_TOKEN, "tenantId", tenantOverride.toString()));
        }
        return objectMapper.writeValueAsString(Map.of("idToken", FAKE_ID_TOKEN));
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    void googleLogin_validToken_existingUser_returnsFullJwt() throws Exception {
        saveExistingUser();
        when(googleTokenVerifier.verify(FAKE_ID_TOKEN))
            .thenReturn(new GoogleUserInfo(GOOGLE_SUB, USER_EMAIL));

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBody(null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.email").value(USER_EMAIL));
    }

    @Test
    void googleLogin_validToken_existingUser_linksGoogleSub() throws Exception {
        User user = saveUserWithoutGoogleSub();
        when(googleTokenVerifier.verify(FAKE_ID_TOKEN))
            .thenReturn(new GoogleUserInfo(GOOGLE_SUB, USER_EMAIL));

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBody(null)))
            .andExpect(status().isOk());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getGoogleSub())
            .isEqualTo(GOOGLE_SUB);
    }

    @Test
    void googleLogin_validToken_newUser_withTenantId_createsUserAndReturnsJwt() throws Exception {
        when(googleTokenVerifier.verify(FAKE_ID_TOKEN))
            .thenReturn(new GoogleUserInfo(GOOGLE_SUB, "newuser@example.com"));

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBody(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.email").value("newuser@example.com"));

        assertThat(userRepository.findByEmail("newuser@example.com")).isPresent();
    }

    @Test
    void googleLogin_validToken_newUser_withoutTenantId_returns422() throws Exception {
        when(googleTokenVerifier.verify(FAKE_ID_TOKEN))
            .thenReturn(new GoogleUserInfo(GOOGLE_SUB, "newuser@example.com"));

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBody(null)))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void googleLogin_validToken_newUser_invalidTenantId_returns404() throws Exception {
        when(googleTokenVerifier.verify(FAKE_ID_TOKEN))
            .thenReturn(new GoogleUserInfo(GOOGLE_SUB, "newuser@example.com"));

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBody(UUID.randomUUID())))
            .andExpect(status().isNotFound());
    }

    @Test
    void googleLogin_invalidToken_returns401() throws Exception {
        when(googleTokenVerifier.verify(anyString()))
            .thenThrow(new InvalidGoogleTokenException());

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBody(null)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Invalid Google ID token."));
    }

    @Test
    void googleLogin_notConfigured_returns400() throws Exception {
        when(googleTokenVerifier.verify(anyString()))
            .thenThrow(new GoogleAuthNotConfiguredException());

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBody(null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Google authentication is not configured."));
    }

    @Test
    void googleLogin_existingUser_2faEnabled_returnsPartialToken() throws Exception {
        User user = saveExistingUser();
        user.setTwoFaEnabled(true);
        user.setTotpSecret("dummy-encrypted-secret");
        userRepository.save(user);

        when(googleTokenVerifier.verify(FAKE_ID_TOKEN))
            .thenReturn(new GoogleUserInfo(GOOGLE_SUB, USER_EMAIL));

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBody(null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.requiresTwoFactor").value(true))
            .andExpect(jsonPath("$.data.partialToken").isNotEmpty());
    }
}
