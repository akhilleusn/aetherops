package com.aetherops.sso;

import com.aetherops.auth.model.AuthResponse;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.auth.service.AuthService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Bridges a successful SAML assertion into AetherOps's own JWT — same "external IdP maps
 * to an internal User, issue our own token" shape as GoogleAuthService uses for Google OAuth.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SamlAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final AuthService authService;

    @Value("${app.sso.frontend-callback-url:http://localhost:8080/sso-callback.html}")
    private String frontendCallbackUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException, ServletException {
        Saml2AuthenticatedPrincipal principal = (Saml2AuthenticatedPrincipal) authentication.getPrincipal();
        UUID tenantId = UUID.fromString(principal.getRelyingPartyRegistrationId());
        String email = principal.getName();

        User user = userRepository.findByEmail(email)
            .orElseGet(() -> userRepository.save(User.builder()
                .email(email)
                .tenantId(tenantId)
                .role(Role.MEMBER)
                .emailVerified(true)
                .build()));

        if (!tenantId.equals(user.getTenantId())) {
            log.warn("SAML assertion for email={} maps to a user in a different tenant (assertion tenant={}, user tenant={}) — rejecting",
                email, tenantId, user.getTenantId());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "User does not belong to this tenant's SSO");
            return;
        }

        AuthResponse authResponse = authService.issueResponse(user);
        String token = authResponse.getRequiresTwoFactor() != null && authResponse.getRequiresTwoFactor()
            ? authResponse.getPartialToken()
            : authResponse.getToken();

        String redirectUrl = frontendCallbackUrl + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        log.info("SAML SSO login succeeded email={} tenantId={}", email, tenantId);
        response.sendRedirect(redirectUrl);
    }
}
