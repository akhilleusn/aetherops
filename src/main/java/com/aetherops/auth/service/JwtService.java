package com.aetherops.auth.service;

import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final int expiryHours;

    public JwtService(
        @Value("${app.jwt.secret}") String secret,
        @Value("${app.jwt.expiry-hours:24}") int expiryHours
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiryHours = expiryHours;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant exp = now.plus(expiryHours, ChronoUnit.HOURS);
        return Jwts.builder()
            .subject(user.getId().toString())
            .claim("tenantId", user.getTenantId().toString())
            .claim("email", user.getEmail())
            .claim("role", user.getRole().name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(key)
            .compact();
    }

    public Instant getExpiresAt(int hours) {
        return Instant.now().plus(hours, ChronoUnit.HOURS);
    }

    public int getExpiryHours() {
        return expiryHours;
    }

    public boolean isTokenValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(getClaims(token).getSubject());
    }

    public UUID extractTenantId(String token) {
        return UUID.fromString(getClaims(token).get("tenantId", String.class));
    }

    public String extractEmail(String token) {
        return getClaims(token).get("email", String.class);
    }

    public Role extractRole(String token) {
        return Role.valueOf(getClaims(token).get("role", String.class));
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
