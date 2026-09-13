package com.aetherops.auth;

import com.aetherops.auth.model.Invitation;
import com.aetherops.auth.model.LoginRequest;
import com.aetherops.auth.model.RegisterRequest;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.repository.InvitationRepository;
import com.aetherops.auth.service.InvitationTokenGenerator;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.auth.service.JwtService;
import com.aetherops.auth.service.LoginAttemptService;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.aetherops.AbstractIntegrationTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired InvitationRepository invitationRepository;
    @Autowired JwtService jwtService;
    @Autowired LoginAttemptService loginAttemptService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        tenantRepository.deleteAll();
        var tenant = tenantRepository.save(
            com.aetherops.tenant.model.Tenant.builder()
                .name("Test Corp")
                .plan(Plan.STARTER)
                .aiMonthlyBudgetCents(0)
                .retentionDays(30)
                .build()
        );
        tenantId = tenant.getId();
    }

    // ── register ──────────────────────────────────────────────────────────

    @Test
    void register_validRequest_returns201WithPendingMessage() throws Exception {
        var req = new RegisterRequest("alice@example.com", "password123", null);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.message").value(
                "Registration successful. Please check your email to verify your account."));
    }

    @Test
    void register_createsUserWithUnverifiedEmail() throws Exception {
        var req = new RegisterRequest("bob@example.com", "password123", null);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());

        var user = userRepository.findByEmail("bob@example.com").orElseThrow();
        assertThat(user.isEmailVerified()).isFalse();
        // No invitationToken was supplied, so registration must create a brand-new tenant —
        // never the pre-existing fixture tenant from setUp().
        assertThat(user.getTenantId()).isNotNull().isNotEqualTo(tenantId);
        assertThat(tenantRepository.findById(user.getTenantId())).isPresent();
        assertThat(user.getEmailVerificationTokenHash()).isNotBlank();
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        var req = new RegisterRequest("dup@example.com", "password123", null);
        String body = objectMapper.writeValueAsString(req);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void register_invalidEmail_returns422() throws Exception {
        var req = new RegisterRequest("not-an-email", "password123", null);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.details.email").isNotEmpty());
    }

    @Test
    void register_shortPassword_returns422() throws Exception {
        var req = new RegisterRequest("carol@example.com", "short", null);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.details.password").isNotEmpty());
    }

    // ── tenant binding (Phase 1 remediation item 2) ──────────────────────────

    @Test
    void register_withForgedTenantId_isRejected() throws Exception {
        // Regression test (b): the caller must never be able to choose which tenant they
        // join by supplying a tenantId directly — even one that names a real, existing tenant.
        String body = "{\"email\":\"dave@example.com\",\"password\":\"password123\",\"tenantId\":\"" + tenantId + "\"}";

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.details.tenantId").isNotEmpty());

        assertThat(userRepository.findByEmail("dave@example.com")).isEmpty();
    }

    @Test
    void register_withForgedTenantSlug_isRejected() throws Exception {
        String body = "{\"email\":\"dave2@example.com\",\"password\":\"password123\",\"tenantSlug\":\"acme\"}";

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.details.tenantSlug").isNotEmpty());
    }

    @Test
    void register_withValidInvitation_joinsInvitedTenant() throws Exception {
        String token = InvitationTokenGenerator.generateToken();
        invitationRepository.save(Invitation.builder()
            .tokenHash(InvitationTokenGenerator.hash(token))
            .tenantId(tenantId)
            .email("invitee@example.com")
            .role(Role.MEMBER)
            .expiresAt(Instant.now().plusSeconds(3600))
            .build());

        var req = new RegisterRequest("invitee@example.com", "password123", token);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());

        var user = userRepository.findByEmail("invitee@example.com").orElseThrow();
        assertThat(user.getTenantId()).isEqualTo(tenantId);
        assertThat(user.getRole()).isEqualTo(Role.MEMBER);
    }

    @Test
    void register_withInvalidInvitationToken_returns400() throws Exception {
        var req = new RegisterRequest("nobody-invited@example.com", "password123", "not-a-real-token");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());

        assertThat(userRepository.findByEmail("nobody-invited@example.com")).isEmpty();
    }

    // ── login ─────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returns200WithToken() throws Exception {
        var regReq = new RegisterRequest("eve@example.com", "mypassword", null);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regReq)))
            .andExpect(status().isCreated());
        verifyUserEmail("eve@example.com");

        var loginReq = new LoginRequest("eve@example.com", "mypassword");
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.email").value("eve@example.com"));
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        var regReq = new RegisterRequest("frank@example.com", "correctpass", null);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regReq)))
            .andExpect(status().isCreated());

        var loginReq = new LoginRequest("frank@example.com", "wrongpass");
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginReq)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void login_unknownEmail_returns401() throws Exception {
        var loginReq = new LoginRequest("nobody@example.com", "anypassword");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginReq)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    // ── lockout ───────────────────────────────────────────────────────────

    @Test
    void login_locksAfter5FailedAttempts() throws Exception {
        String email = "locktest-" + UUID.randomUUID() + "@example.com";
        loginAttemptService.recordSuccess(email); // ensure clean state
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, "correctpass", null))))
            .andExpect(status().isCreated());

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new LoginRequest(email, "wrongpass"))))
                .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, "wrongpass"))))
            .andExpect(status().isLocked());
    }

    @Test
    void lockedAccount_returns423WithLockMessage() throws Exception {
        String email = "lockmsg-" + UUID.randomUUID() + "@example.com";
        loginAttemptService.recordSuccess(email);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, "correctpass", null))))
            .andExpect(status().isCreated());

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new LoginRequest(email, "wrongpass"))))
                .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, "wrongpass"))))
            .andExpect(status().isLocked())
            .andExpect(jsonPath("$.message", containsString("Account locked. Try again in")));
    }

    @Test
    void successfulLogin_resetsFailedCounter() throws Exception {
        String email = "lockreset-" + UUID.randomUUID() + "@example.com";
        loginAttemptService.recordSuccess(email);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, "correctpass", null))))
            .andExpect(status().isCreated());
        verifyUserEmail(email);

        // 4 failures
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new LoginRequest(email, "wrongpass"))))
                .andExpect(status().isUnauthorized());
        }
        // success resets counter
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, "correctpass"))))
            .andExpect(status().isOk());

        // first failure after reset must be 401, not 423
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, "wrongpass"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void firstFourFailures_stillReturn401() throws Exception {
        String email = "lock4-" + UUID.randomUUID() + "@example.com";
        loginAttemptService.recordSuccess(email);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, "correctpass", null))))
            .andExpect(status().isCreated());

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new LoginRequest(email, "wrongpass"))))
                .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void login_unknownEmail_sameErrorShapeAsWrongPassword() throws Exception {
        // Both wrong-email and wrong-password must return the same message to avoid leaking account existence
        var regReq = new RegisterRequest("grace@example.com", "correctpass", null);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regReq)))
            .andExpect(status().isCreated());

        String wrongPassBody = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest("grace@example.com", "badpass"))))
            .andReturn().getResponse().getContentAsString();

        String unknownBody = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest("unknown@example.com", "badpass"))))
            .andReturn().getResponse().getContentAsString();

        JsonNode wrongPassNode = objectMapper.readTree(wrongPassBody);
        JsonNode unknownNode = objectMapper.readTree(unknownBody);

        assertThat(wrongPassNode.path("message").asText())
            .isEqualTo(unknownNode.path("message").asText());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void verifyUserEmail(String email) {
        userRepository.findByEmail(email).ifPresent(u -> {
            u.setEmailVerified(true);
            userRepository.save(u);
        });
    }
}
