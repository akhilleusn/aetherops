package com.aetherops.auth.service;

import com.aetherops.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Split out from RefreshTokenService for one reason: REQUIRES_NEW only works through a real
 * Spring AOP proxy, and RefreshTokenService.rotate() calling this on itself (self-invocation)
 * would silently bypass the proxy and run in the SAME transaction — which then rolls back this
 * revoke too, since rotate() immediately throws RefreshTokenReuseException to signal the
 * caller. This must commit independently, BEFORE that throw, or the reuse-triggered family
 * revocation never actually survives.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenFamilyRevocationService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(UUID familyId) {
        refreshTokenRepository.revokeFamily(familyId);
    }
}
