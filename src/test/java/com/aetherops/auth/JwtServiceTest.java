package com.aetherops.auth;

import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.service.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "aetherops-test-secret-key-minimum-32-chars-long";

    private JwtService jwtService;
    private User testUser;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 24);
        testUser = User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .passwordHash("irrelevant")
            .role(Role.MEMBER)
            .tenantId(UUID.randomUUID())
            .build();
    }

    /** Builds a token, signed with the same key JwtService uses, whose exp is a fixed offset in the past. */
    private String tokenThatExpiredSecondsAgo(long secondsAgo) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant exp = Instant.now().minusSeconds(secondsAgo);
        return Jwts.builder()
            .subject(testUser.getId().toString())
            .claim("tenantId", testUser.getTenantId().toString())
            .claim("email", testUser.getEmail())
            .claim("role", testUser.getRole().name())
            .issuedAt(Date.from(exp.minusSeconds(3600)))
            .expiration(Date.from(exp))
            .signWith(key)
            .compact();
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

    /**
     * Phase 3 finding 8 (see STEP_REPORT.md): JwtService now allows 30s of clock-skew
     * tolerance, so "expired" here must be well beyond that tolerance to unambiguously test
     * expiry rejection rather than racing the tolerance window (an exp of "now", as this test
     * used to construct via a 0-hour-expiry token, would now be accepted).
     */
    @Test
    void isTokenValid_expiredBeyondClockSkewTolerance_returnsFalse() {
        String token = tokenThatExpiredSecondsAgo(60);
        assertThat(jwtService.isTokenValid(token)).isFalse();
    }

    /**
     * Phase 3 finding 8: the actual point of the fix — a token that expired only moments ago
     * (well within the 30s tolerance) must still validate, so ordinary clock drift across
     * replicas doesn't produce spurious 401s right at the expiry boundary.
     */
    @Test
    void isTokenValid_expiredWithinClockSkewTolerance_returnsTrue() {
        String token = tokenThatExpiredSecondsAgo(10);
        assertThat(jwtService.isTokenValid(token)).isTrue();
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
