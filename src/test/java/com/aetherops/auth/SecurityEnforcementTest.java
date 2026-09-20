package com.aetherops.auth;

import com.aetherops.auth.model.Invitation;
import com.aetherops.auth.model.RegisterRequest;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.repository.InvitationRepository;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.auth.service.InvitationTokenGenerator;
import com.aetherops.auth.service.JwtService;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SecurityEnforcementTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired InvitationRepository invitationRepository;
    @Autowired JwtService jwtService;

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        tenantRepository.deleteAll();
        tenant = tenantRepository.save(Tenant.builder()
            .name("Security Test Corp")
            .plan(Plan.STARTER)
            .aiMonthlyBudgetCents(0)
            .retentionDays(30)
            .build());
    }

    private String createInvitation(String email) {
        String token = InvitationTokenGenerator.generateToken();
        invitationRepository.save(Invitation.builder()
            .tokenHash(InvitationTokenGenerator.hash(token))
            .tenantId(tenant.getId())
            .email(email)
            .role(Role.MEMBER)
            .expiresAt(Instant.now().plusSeconds(3600))
            .build());
        return token;
    }

    @Test
    void protectedEndpoint_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/tenants"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void createTenant_noToken_returns401() throws Exception {
        // Phase 1 remediation item 3: an unauthenticated caller must not be able to create a
        // tenant — that bypasses plan limits/billing and hands an attacker a foothold tenant.
        // Uses the real SecurityConfig (this class doesn't set @ActiveProfiles("test")),
        // unlike TenantIntegrationTest, whose "test" profile loads a permit-all
        // TestSecurityConfig and so cannot actually exercise this enforcement.
        String body = "{\"name\":\"Acme Corp\",\"plan\":\"STARTER\",\"aiMonthlyBudgetCents\":5000,\"retentionDays\":30}";

        mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/tenants")
                .header("Authorization", "Bearer this.is.garbage"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Invalid or expired token"));
    }

    @Test
    void protectedEndpoint_validToken_returns2xx() throws Exception {
        var regReq = new RegisterRequest("sectest@example.com", "password123", createInvitation("sectest@example.com"));
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regReq)))
            .andExpect(status().isCreated());

        // Register no longer returns a token — verify email directly, then generate token
        com.aetherops.auth.model.User user = userRepository.findByEmail("sectest@example.com").orElseThrow();
        user.setEmailVerified(true);
        user = userRepository.save(user);
        String token = jwtService.generateToken(user);

        mockMvc.perform(get("/api/v1/tenants")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    void authRegisterEndpoint_noToken_isPublic() throws Exception {
        // "Public" means Spring Security doesn't block an unauthenticated caller from reaching
        // this endpoint -- not that registration succeeds without an invitation. Registration
        // requires a non-blank invitationToken now, so a request without one is correctly
        // rejected by business validation (422), not by the security filter chain (401/403);
        // that distinction is exactly what this test needs to keep proving.
        var req = new RegisterRequest("pub@example.com", "password123", null);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void actuatorHealth_noToken_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }
}
