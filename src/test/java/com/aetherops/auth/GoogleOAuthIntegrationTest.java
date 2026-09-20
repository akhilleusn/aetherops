package com.aetherops.auth;

import com.aetherops.auth.model.GoogleUserInfo;
import com.aetherops.auth.model.Invitation;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.InvitationRepository;
import com.aetherops.auth.service.InvitationTokenGenerator;
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

import java.time.Instant;
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
    @Autowired InvitationRepository invitationRepository;
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

    private String googleBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of("idToken", FAKE_ID_TOKEN));
    }

    private String googleBodyWithForgedTenantId(UUID forgedTenantId) throws Exception {
        return objectMapper.writeValueAsString(
            Map.of("idToken", FAKE_ID_TOKEN, "tenantId", forgedTenantId.toString()));
    }

    private String googleBodyWithInvitation(String invitationToken) throws Exception {
        return objectMapper.writeValueAsString(
            Map.of("idToken", FAKE_ID_TOKEN, "invitationToken", invitationToken));
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    void googleLogin_validToken_existingUser_returnsFullJwt() throws Exception {
        saveExistingUser();
        when(googleTokenVerifier.verify(FAKE_ID_TOKEN))
            .thenReturn(new GoogleUserInfo(GOOGLE_SUB, USER_EMAIL));

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBody()))
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
                .content(googleBody()))
            .andExpect(status().isOk());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getGoogleSub())
            .isEqualTo(GOOGLE_SUB);
    }

    // ── tenant binding (Phase 1 remediation item 2) ──────────────────────────

    @Test
    void googleLogin_validToken_newUser_withoutInvitation_isRejected() throws Exception {
        // Registration is by invitation only (see AuthService.register / GoogleAuthService
        // .authenticate) — a brand-new Google-verified email with no invitation must not be
        // able to self-serve a new tenant, the same rule the password-registration path
        // enforces via RegisterRequest's @NotBlank invitationToken.
        when(googleTokenVerifier.verify(FAKE_ID_TOKEN))
            .thenReturn(new GoogleUserInfo(GOOGLE_SUB, "newuser@example.com"));

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBody()))
            .andExpect(status().isBadRequest());

        assertThat(userRepository.findByEmail("newuser@example.com")).isEmpty();
    }

    @Test
    void googleLogin_validToken_newUser_withForgedTenantId_isRejected() throws Exception {
        when(googleTokenVerifier.verify(FAKE_ID_TOKEN))
            .thenReturn(new GoogleUserInfo(GOOGLE_SUB, "newuser@example.com"));

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBodyWithForgedTenantId(tenantId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.details.tenantId").isNotEmpty());

        assertThat(userRepository.findByEmail("newuser@example.com")).isEmpty();
    }

    @Test
    void googleLogin_newUser_withForgedVictimTenantId_doesNotJoinVictimTenant() throws Exception {
        // Regression test (c): a "victim" already belongs to tenantId (set up below). An
        // attacker, authenticating as a brand-new (never-before-seen) Google-verified email,
        // must not be able to force their way into the victim's tenant by naming it directly.
        userRepository.save(User.builder()
            .email("victim@example.com")
            .role(Role.MEMBER)
            .tenantId(tenantId)
            .build());

        when(googleTokenVerifier.verify(FAKE_ID_TOKEN))
            .thenReturn(new GoogleUserInfo("attacker-google-sub", "attacker@example.com"));

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBodyWithForgedTenantId(tenantId)))
            .andExpect(status().isUnprocessableEntity());

        assertThat(userRepository.findByEmail("attacker@example.com")).isEmpty();
    }

    @Test
    void googleLogin_validToken_newUser_withValidInvitation_joinsInvitedTenant() throws Exception {
        String token = InvitationTokenGenerator.generateToken();
        invitationRepository.save(Invitation.builder()
            .tokenHash(InvitationTokenGenerator.hash(token))
            .tenantId(tenantId)
            .email("invitee@example.com")
            .role(Role.MEMBER)
            .expiresAt(Instant.now().plusSeconds(3600))
            .build());

        when(googleTokenVerifier.verify(FAKE_ID_TOKEN))
            .thenReturn(new GoogleUserInfo("invitee-google-sub", "invitee@example.com"));

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBodyWithInvitation(token)))
            .andExpect(status().isOk());

        User created = userRepository.findByEmail("invitee@example.com").orElseThrow();
        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getRole()).isEqualTo(Role.MEMBER);
    }

    @Test
    void googleLogin_validToken_newUser_withInvalidInvitationToken_returns400() throws Exception {
        when(googleTokenVerifier.verify(FAKE_ID_TOKEN))
            .thenReturn(new GoogleUserInfo("nobody-google-sub", "nobody-invited@example.com"));

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBodyWithInvitation("not-a-real-token")))
            .andExpect(status().isBadRequest());

        assertThat(userRepository.findByEmail("nobody-invited@example.com")).isEmpty();
    }

    @Test
    void googleLogin_invalidToken_returns401() throws Exception {
        when(googleTokenVerifier.verify(anyString()))
            .thenThrow(new InvalidGoogleTokenException());

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBody()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Invalid Google ID token."));
    }

    @Test
    void googleLogin_notConfigured_returns400() throws Exception {
        when(googleTokenVerifier.verify(anyString()))
            .thenThrow(new GoogleAuthNotConfiguredException());

        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleBody()))
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
                .content(googleBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.requiresTwoFactor").value(true))
            .andExpect(jsonPath("$.data.partialToken").isNotEmpty());
    }
}
