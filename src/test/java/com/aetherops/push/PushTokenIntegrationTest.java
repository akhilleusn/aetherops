package com.aetherops.push;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.auth.model.Role;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 1 Step E, item 1 (finding 7): POST /api/v1/users/{userId}/push-token used to take
 * userId from the path and tenantId from the body — both pure client input, no self-check.
 * Any tenant member could overwrite another member's push token, silently un-paging them.
 * Now userId/tenantId come only from the authenticated principal (CurrentUserContext /
 * TenantContext), and the path no longer carries a userId segment at all.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PushTokenIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PushDeviceTokenRepository tokenRepository;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
    }

    @Test
    void register_persistsUnderTheCallersOwnIdentity() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String expoPushToken = "ExponentPushToken[" + UUID.randomUUID() + "]";

        mockMvc.perform(post("/api/v1/users/me/push-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("expoPushToken", expoPushToken, "platform", "ios")))
                .with(asTenant(tenantId, userId, Role.MEMBER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value(userId.toString()))
            .andExpect(jsonPath("$.data.tenantId").value(tenantId.toString()));

        PushDeviceToken saved = withTenant(tenantId, () -> tokenRepository.findByExpoPushToken(expoPushToken))
            .orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void register_smuggledUserIdAndTenantIdInBody_areSilentlyIgnored_notHonored() throws Exception {
        // Simulates exactly what the old vulnerable API accepted: a caller (user A) trying
        // to register/overwrite a token under victim user B's identity, in victim tenant B.
        UUID callerTenantId = UUID.randomUUID();
        UUID callerUserId = UUID.randomUUID();
        UUID victimTenantId = UUID.randomUUID();
        UUID victimUserId = UUID.randomUUID();
        String expoPushToken = "ExponentPushToken[" + UUID.randomUUID() + "]";

        String maliciousBody = objectMapper.writeValueAsString(Map.of(
            "expoPushToken", expoPushToken,
            "platform", "ios",
            // These two fields don't exist on RegisterPushTokenRequest anymore — Jackson
            // silently drops unknown properties by default. This IS the point: there is no
            // slot for an attacker-chosen identity to land in, at any layer.
            "userId", victimUserId.toString(),
            "tenantId", victimTenantId.toString()
        ));

        mockMvc.perform(post("/api/v1/users/me/push-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(maliciousBody)
                .with(asTenant(callerTenantId, callerUserId, Role.MEMBER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value(callerUserId.toString()))
            .andExpect(jsonPath("$.data.tenantId").value(callerTenantId.toString()));

        PushDeviceToken saved = withTenant(callerTenantId, () -> tokenRepository.findByExpoPushToken(expoPushToken))
            .orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(callerUserId);
        assertThat(saved.getTenantId()).isEqualTo(callerTenantId);
        assertThat(saved.getUserId()).isNotEqualTo(victimUserId);
        assertThat(saved.getTenantId()).isNotEqualTo(victimTenantId);
    }

    @Test
    void register_oldUserIdPathVariable_noLongerExists_returns404() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/users/{userId}/push-token", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("expoPushToken", "ExponentPushToken[gone]")))
                .with(asTenant(tenantId, userId, Role.MEMBER)))
            .andExpect(status().isNotFound());
    }
}
