package com.aetherops.config;

import com.aetherops.apikey.ApiKeyAuthFilter;
import com.aetherops.auth.security.CurrentUserContextFilter;
import com.aetherops.auth.security.JwtAuthFilter;
import com.aetherops.auth.service.JwtService;
import com.aetherops.common.response.ErrorResponse;
import com.aetherops.tenant.TenantContextFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@Profile("!test")
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final TenantContextFilter tenantContextFilter;
    private final CurrentUserContextFilter currentUserContextFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtService, objectMapper);

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/forgot-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/reset-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/2fa/verify").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/google").permitAll()
                .requestMatchers(HttpMethod.GET,  "/api/v1/auth/google/client-id").permitAll()
                .requestMatchers(HttpMethod.GET,  "/api/v1/auth/verify-email").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/resend-verification").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/tenants").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/slack/oauth/callback").permitAll()
                .requestMatchers(HttpMethod.POST, "/slack/interactivity").permitAll()
                .requestMatchers(HttpMethod.POST, "/slack/commands").permitAll()
                .requestMatchers(HttpMethod.POST, "/webhooks/stripe").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/status-pages/**").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**", "/v3/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/").permitAll()
                .requestMatchers("/*.html").permitAll()
                .requestMatchers("/weblogos/**").permitAll()
                .requestMatchers("/api/v1/demo/**").permitAll()
                .anyRequest().authenticated()
            )
            .headers(headers -> headers
                .contentTypeOptions(ct -> {})
                .frameOptions(fo -> fo.deny())
                .xssProtection(xss -> {})
                .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .addHeaderWriter((request, response) ->
                    response.setHeader("X-XSS-Protection", "1; mode=block")
                )
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyAuthFilter, JwtAuthFilter.class)
            .addFilterAfter(tenantContextFilter, JwtAuthFilter.class)
            .addFilterAfter(currentUserContextFilter, JwtAuthFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(
                        response.getOutputStream(),
                        ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Authentication required", request.getRequestURI())
                    );
                })
            );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
            "http://localhost:8080",
            "https://aetherops-production.up.railway.app"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Api-Key", "X-Request-Id"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
