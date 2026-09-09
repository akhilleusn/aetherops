package com.aetherops.auth.service;

import com.aetherops.auth.model.RefreshToken;
import com.aetherops.auth.repository.RefreshTokenRepository;
import com.aetherops.common.exception.InvalidRefreshTokenException;
import com.aetherops.common.exception.RefreshTokenReuseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Phase 1 Step C, item 4: refresh tokens used to be stored raw, and the same token stayed
 * valid and reusable indefinitely (no rotation at all) until its 7-day expiry or an explicit
 * revoke. Now: only a SHA-256 hash is ever persisted, every successful use rotates the token
 * (issues a new one, marks the old one used), and presenting an already-used token — the
 * signal a token has leaked — revokes every token in its family and forces re-login.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private static final int REFRESH_TOKEN_EXPIRY_DAYS = 7;

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenFamilyRevocationService familyRevocationService;

    public record IssuedToken(String rawToken, Instant expiresAt) {}

    public record RotationResult(UUID userId, UUID tenantId, IssuedToken next) {}

    /** Issues the first token of a brand-new family — e.g. at login. */
    @Transactional
    public IssuedToken issue(UUID userId, UUID tenantId) {
        return issue(userId, tenantId, UUID.randomUUID());
    }

    private IssuedToken issue(UUID userId, UUID tenantId, UUID familyId) {
        String rawToken = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(REFRESH_TOKEN_EXPIRY_DAYS * 24 * 60 * 60);

        RefreshToken token = RefreshToken.builder()
                .tokenHash(sha256(rawToken))
                .familyId(familyId)
                .userId(userId)
                .tenantId(tenantId)
                .expiresAt(expiresAt)
                .revoked(false)
                .used(false)
                .build();
        refreshTokenRepository.save(token);
        return new IssuedToken(rawToken, expiresAt);
    }

    /**
     * Validates the presented token and rotates it: the old one is marked used, a new one is
     * issued in the same family. Reuse of an already-used token revokes the whole family.
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshToken existing = refreshTokenRepository.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token not found"));

        if (existing.isUsed()) {
            log.warn("Refresh token reuse detected userId={} familyId={} — revoking family",
                    existing.getUserId(), existing.getFamilyId());
            // Must commit in its own transaction — see RefreshTokenFamilyRevocationService's
            // javadoc — since this method throws immediately after, which would otherwise
            // roll back the revoke along with everything else in this transaction.
            familyRevocationService.revokeFamily(existing.getFamilyId());
            throw new RefreshTokenReuseException();
        }

        if (existing.isRevoked()) {
            throw new InvalidRefreshTokenException("Refresh token has been revoked");
        }

        if (existing.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        existing.setUsed(true);
        refreshTokenRepository.save(existing);

        IssuedToken next = issue(existing.getUserId(), existing.getTenantId(), existing.getFamilyId());
        return new RotationResult(existing.getUserId(), existing.getTenantId(), next);
    }

    @Transactional
    public void revokeRefreshToken(String rawToken) {
        refreshTokenRepository.findByTokenHash(sha256(rawToken)).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        });
    }

    @Transactional
    public void revokeAllUserTokens(UUID userId) {
        refreshTokenRepository.deleteAllByUserId(userId);
        log.info("Revoked all refresh tokens for userId={}", userId);
    }

    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
