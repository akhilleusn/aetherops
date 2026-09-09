package com.aetherops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Not profile-restricted (unlike SecurityConfig/TestSecurityConfig) — @PreAuthorize must be
 * enforced identically in every profile, including "test", or role checks would be silently
 * inert in the integration test suite while appearing enforced in production.
 *
 * Confirmed load-bearing, not decorative: RoleEnforcementTest.ownerOnlyEndpoint_memberCaller_returns403
 * and .ownerOrAdminEndpoint_memberCaller_returns403 were run with @EnableMethodSecurity
 * commented out below and got 201/200 instead of 403 — proving @PreAuthorize is inert without
 * this. Both pass now that it's back.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}
