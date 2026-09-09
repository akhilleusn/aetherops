package com.aetherops.auth;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.auth.service.RefreshTokenService;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 1 Step C, item 4: refresh tokens used to stay valid and reusable indefinitely with no
 * rotation at all. Note: nothing in the login/2FA/Google-auth flow issues a refresh token
 * today (see STEP_REPORT.md) — that's a separate, pre-existing gap left untouched here, so
 * these tests seed a token directly via RefreshTokenService, the same way the endpoint would
 * receive one from any future caller.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshTokenRotationIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired RefreshTokenService refreshTokenService;

    private UUID userId;
    private UUID tenantId;

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

        User user = userRepository.save(User.builder()
            .email("refresh-test@example.com")
            .role(Role.MEMBER)
            .tenantId(tenantId)
            .emailVerified(true)
            .build());
        userId = user.getId();
    }

    @Test
    void refresh_validToken_rotatesAndReturnsNewToken() throws Exception {
        String rawToken = refreshTokenService.issue(userId, tenantId).rawToken();

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", rawToken))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
            .andExpect(jsonPath("$.data.refreshToken").value(not(rawToken)));
    }

    @Test
    void refresh_sameTokenTwice_secondCallRejected() throws Exception {
        String rawToken = refreshTokenService.issue(userId, tenantId).rawToken();

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", rawToken))))
            .andExpect(status().isOk());

        // Same token again — it was already rotated away from on the first call.
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", rawToken))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_reusedToken_revokesEntireFamily_forcingReLogin() throws Exception {
        String originalToken = refreshTokenService.issue(userId, tenantId).rawToken();

        // First use — succeeds, rotates to a new token in the same family.
        String body = mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", originalToken))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String rotatedToken = objectMapper.readTree(body).at("/data/refreshToken").asText();

        // Reuse of the already-rotated-away-from original — the leak signal.
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", originalToken))))
            .andExpect(status().isUnauthorized());

        // Proof the WHOLE family was revoked, not just the reused token: the token that the
        // legitimate first call rotated to is now dead too, even though it was never reused
        // and was never itself presented improperly.
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", rotatedToken))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_unknownToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", "not-a-real-token"))))
            .andExpect(status().isUnauthorized());
    }
}
