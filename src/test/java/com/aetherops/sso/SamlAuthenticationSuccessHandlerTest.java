package com.aetherops.sso;

import com.aetherops.auth.model.AuthResponse;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SamlAuthenticationSuccessHandlerTest {

    @Mock private UserRepository userRepository;
    @Mock private AuthService authService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private Authentication authentication;
    @Mock private Saml2AuthenticatedPrincipal principal;

    @InjectMocks
    private SamlAuthenticationSuccessHandler successHandler;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(successHandler, "frontendCallbackUrl", "https://app.aetherops.dev/sso-callback.html");
        when(authentication.getPrincipal()).thenReturn(principal);
        when(principal.getRelyingPartyRegistrationId()).thenReturn(tenantId.toString());
    }

    @Test
    void onAuthenticationSuccess_existingUserInCorrectTenant_issuesTokenAndRedirects() throws Exception {
        when(principal.getName()).thenReturn("alice@acme.com");
        User user = User.builder().id(UUID.randomUUID()).email("alice@acme.com").tenantId(tenantId).role(Role.MEMBER).build();
        when(userRepository.findByEmail("alice@acme.com")).thenReturn(Optional.of(user));
        when(authService.issueResponse(user)).thenReturn(AuthResponse.builder().token("jwt-token-123").build());

        successHandler.onAuthenticationSuccess(request, response, authentication);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(urlCaptor.capture());
        assertThat(urlCaptor.getValue()).contains("token=jwt-token-123");
        assertThat(urlCaptor.getValue()).startsWith("https://app.aetherops.dev/sso-callback.html");
    }

    @Test
    void onAuthenticationSuccess_unknownEmail_autoProvisionsUser() throws Exception {
        when(principal.getName()).thenReturn("newuser@acme.com");
        when(userRepository.findByEmail("newuser@acme.com")).thenReturn(Optional.empty());
        User newUser = User.builder().id(UUID.randomUUID()).email("newuser@acme.com").tenantId(tenantId).role(Role.MEMBER).build();
        when(userRepository.save(any())).thenReturn(newUser);
        when(authService.issueResponse(newUser)).thenReturn(AuthResponse.builder().token("jwt-token-456").build());

        successHandler.onAuthenticationSuccess(request, response, authentication);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getTenantId()).isEqualTo(tenantId);
        assertThat(userCaptor.getValue().isEmailVerified()).isTrue();
        verify(response).sendRedirect(contains("token=jwt-token-456"));
    }

    @Test
    void onAuthenticationSuccess_existingUserInDifferentTenant_rejectsWithForbidden() throws Exception {
        when(principal.getName()).thenReturn("bob@othercompany.com");
        UUID differentTenant = UUID.randomUUID();
        User userFromOtherTenant = User.builder()
            .id(UUID.randomUUID()).email("bob@othercompany.com").tenantId(differentTenant).role(Role.MEMBER).build();
        when(userRepository.findByEmail("bob@othercompany.com")).thenReturn(Optional.of(userFromOtherTenant));

        successHandler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendError(eq(HttpServletResponse.SC_FORBIDDEN), any());
        verifyNoInteractions(authService);
        verify(response, never()).sendRedirect(any());
    }

    @Test
    void onAuthenticationSuccess_userRequiresTwoFactor_redirectsWithPartialTokenNotFullToken() throws Exception {
        when(principal.getName()).thenReturn("alice@acme.com");
        User user = User.builder().id(UUID.randomUUID()).email("alice@acme.com").tenantId(tenantId).role(Role.MEMBER).build();
        when(userRepository.findByEmail("alice@acme.com")).thenReturn(Optional.of(user));
        when(authService.issueResponse(user)).thenReturn(AuthResponse.builder()
            .requiresTwoFactor(true).partialToken("partial-token-789").token("full-token-should-not-be-used").build());

        successHandler.onAuthenticationSuccess(request, response, authentication);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(urlCaptor.capture());
        assertThat(urlCaptor.getValue()).contains("token=partial-token-789");
        assertThat(urlCaptor.getValue()).doesNotContain("full-token-should-not-be-used");
    }
}
