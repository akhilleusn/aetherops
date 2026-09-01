package com.aetherops.sso;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Separate, narrowly-scoped filter chain for the SAML SSO endpoints. Evaluated before the
 * main SecurityConfig chain (which has no explicit @Order and so defaults to lowest
 * precedence) — requests to /saml2/** and /login/saml2/** never reach the JWT-based chain.
 * These paths are inherently pre-authentication; the SAML assertion's signature (verified
 * against the tenant's registered IdP certificate) is what stands in for the usual JWT check.
 */
@Configuration
@Profile("!test")
@RequiredArgsConstructor
public class SamlSecurityConfig {

    private final RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;
    private final SamlAuthenticationSuccessHandler samlAuthenticationSuccessHandler;

    @Bean
    @Order(1)
    public SecurityFilterChain samlFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/saml2/**", "/login/saml2/**")
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .saml2Login(saml2 -> saml2
                .relyingPartyRegistrationRepository(relyingPartyRegistrationRepository)
                .successHandler(samlAuthenticationSuccessHandler));

        return http.build();
    }
}
