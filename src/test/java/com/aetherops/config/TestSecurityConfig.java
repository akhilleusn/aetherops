package com.aetherops.config;

import com.aetherops.auth.security.JwtAuthFilter;
import com.aetherops.auth.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stays permit-all at the HTTP-authorization layer (authorizeHttpRequests) so existing tests
 * that never send a token keep working unauthenticated. But JwtAuthFilter is now wired in here
 * too — without it, SecurityContextHolder would never carry the JWT's role authority under the
 * "test" profile, and every @PreAuthorize check added for Phase 1 Step B would evaluate against
 * an anonymous/absent Authentication for every test, regardless of the role asTenant() encodes
 * into the token. That would make method security look "on" while actually gating nothing.
 */
@Configuration
@Profile("test")
@RequiredArgsConstructor
public class TestSecurityConfig {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new JwtAuthFilter(jwtService, objectMapper), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
