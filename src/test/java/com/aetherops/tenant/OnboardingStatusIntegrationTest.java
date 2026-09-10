package com.aetherops.tenant;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.auth.model.Invitation;
import com.aetherops.auth.model.RegisterRequest;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.repository.InvitationRepository;
import com.aetherops.auth.service.InvitationTokenGenerator;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.slack.SlackWorkspace;
import com.aetherops.slack.SlackWorkspaceRepository;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnboardingStatusIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired InvitationRepository invitationRepository;
    @Autowired SlackWorkspaceRepository slackWorkspaceRepository;
    @Autowired IncidentRepository incidentRepository;
    @Autowired LogEventRepository logEventRepository;

    @BeforeEach
    void setUp() {
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
        slackWorkspaceRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void freshTenant_nextStepIsCreateAdminUser() throws Exception {
        UUID tenantId = createTenant();

        mockMvc.perform(get("/api/v1/tenants/{id}/onboarding/status", tenantId).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.adminUserCreated").value(false))
            .andExpect(jsonPath("$.data.slackConnected").value(false))
            .andExpect(jsonPath("$.data.firstIncidentSeen").value(false))
            .andExpect(jsonPath("$.data.onboardingComplete").value(false))
            .andExpect(jsonPath("$.data.nextStep").value("CREATE_ADMIN_USER"));
    }

    @Test
    void afterAdminRegistered_nextStepIsConnectSlack() throws Exception {
        UUID tenantId = createTenant();
        registerAdmin(tenantId);

        mockMvc.perform(get("/api/v1/tenants/{id}/onboarding/status", tenantId).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.adminUserCreated").value(true))
            .andExpect(jsonPath("$.data.slackConnected").value(false))
            .andExpect(jsonPath("$.data.nextStep").value("CONNECT_SLACK"));
    }

    @Test
    void afterSlackConnected_nextStepIsSeeFirstIncident() throws Exception {
        UUID tenantId = createTenant();
        registerAdmin(tenantId);
        connectSlack(tenantId);

        mockMvc.perform(get("/api/v1/tenants/{id}/onboarding/status", tenantId).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.slackConnected").value(true))
            .andExpect(jsonPath("$.data.firstIncidentSeen").value(false))
            .andExpect(jsonPath("$.data.nextStep").value("SEE_FIRST_INCIDENT"));
    }

    @Test
    void afterFirstIncident_onboardingIsComplete() throws Exception {
        UUID tenantId = createTenant();
        registerAdmin(tenantId);
        connectSlack(tenantId);
        ingestEvent(tenantId, "onboarding completion incident");

        mockMvc.perform(get("/api/v1/tenants/{id}/onboarding/status", tenantId).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.firstIncidentSeen").value(true))
            .andExpect(jsonPath("$.data.onboardingComplete").value(true))
            .andExpect(jsonPath("$.data.nextStep").doesNotExist());
    }

    @Test
    void unknownTenant_returns404() throws Exception {
        UUID unknownTenantId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        mockMvc.perform(get("/api/v1/tenants/{id}/onboarding/status", unknownTenantId)
                .with(asTenant(unknownTenantId)))
            .andExpect(status().isNotFound());
    }

    private UUID createTenant() throws Exception {
        var request = new CreateTenantRequest("Onboarding Test Tenant " + UUID.randomUUID(), 30);
        String body = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).at("/data/id").asText());
    }

    private void registerAdmin(UUID tenantId) throws Exception {
        // Registration can no longer take a tenantId directly (Phase 1 remediation item 2) —
        // join this specific pre-created tenant via a server-issued invitation instead.
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        String token = InvitationTokenGenerator.generateToken();
        invitationRepository.save(Invitation.builder()
            .tokenHash(InvitationTokenGenerator.hash(token))
            .tenantId(tenantId)
            .email(email)
            .role(Role.OWNER)
            .expiresAt(Instant.now().plusSeconds(3600))
            .build());

        var request = new RegisterRequest(email, "password123", token);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());
    }

    private void connectSlack(UUID tenantId) {
        TenantContext.set(tenantId);
        slackWorkspaceRepository.save(SlackWorkspace.builder()
            .tenantId(tenantId)
            .teamId("T12345")
            .teamName("Test Workspace")
            .botUserId("U12345")
            .encryptedBotToken("encrypted-token-not-real")
            .build());
    }

    private void ingestEvent(UUID tenantId, String message) throws Exception {
        LogEventRequest req = new LogEventRequest();
        req.setTenantId(tenantId);
        req.setServiceId("onboarding-test-svc");
        req.setLevel(LogLevel.ERROR);
        req.setMessage(message);
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated());
    }
}
