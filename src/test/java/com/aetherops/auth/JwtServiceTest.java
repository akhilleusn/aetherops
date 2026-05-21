package com.aetherops.auth;

import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;
    private User testUser;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService("aetherops-test-secret-key-minimum-32-chars-long", 24);
        testUser = User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .passwordHash("irrelevant")
            .role(Role.MEMBER)
            .tenantId(UUID.randomUUID())
            .build();
    }

    @Test
    void generateToken_isNonNull() {
        assertThat(jwtService.generateToken(testUser)).isNotBlank();
    }

    @Test
    void isTokenValid_withValidToken_returnsTrue() {
        String token = jwtService.generateToken(testUser);
        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    void isTokenValid_withTamperedToken_returnsFalse() {
        String token = jwtService.generateToken(testUser);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertThat(jwtService.isTokenValid(tampered)).isFalse();
    }

    @Test
    void isTokenValid_withGarbage_returnsFalse() {
        assertThat(jwtService.isTokenValid("not.a.jwt")).isFalse();
    }

    @Test
    void isTokenValid_withExpiredToken_returnsFalse() {
        JwtService shortLived = new JwtService("aetherops-test-secret-key-minimum-32-chars-long", 0);
        String token = shortLived.generateToken(testUser);
        assertThat(shortLived.isTokenValid(token)).isFalse();
    }

    @Test
    void extractUserId_roundTrip() {
        String token = jwtService.generateToken(testUser);
        assertThat(jwtService.extractUserId(token)).isEqualTo(testUser.getId());
    }

    @Test
    void extractTenantId_roundTrip() {
        String token = jwtService.generateToken(testUser);
        assertThat(jwtService.extractTenantId(token)).isEqualTo(testUser.getTenantId());
    }

    @Test
    void extractEmail_roundTrip() {
        String token = jwtService.generateToken(testUser);
        assertThat(jwtService.extractEmail(token)).isEqualTo(testUser.getEmail());
    }

    @Test
    void extractRole_roundTrip() {
        String token = jwtService.generateToken(testUser);
        assertThat(jwtService.extractRole(token)).isEqualTo(Role.MEMBER);
    }
}
