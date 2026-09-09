package com.aetherops.auth.service;

import com.aetherops.auth.model.Invitation;
import com.aetherops.auth.repository.InvitationRepository;
import com.aetherops.common.exception.InvalidInvitationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Redeems server-issued invitations — the only way a new registrant may join an existing
 * tenant (see AuthService.register / GoogleAuthService.authenticate). No issuance endpoint
 * exists yet; rows are created directly (tests, or a future admin-facing endpoint) via
 * InvitationTokenGenerator, which is also the only source of tokenHash values.
 */
@Service
@RequiredArgsConstructor
public class InvitationService {

    private static final String GENERIC_ERROR = "Invalid or expired invitation";

    private final InvitationRepository invitationRepository;

    @Transactional
    public Invitation redeem(String token, String email) {
        String presentedHash = InvitationTokenGenerator.hash(token);
        Invitation invitation = invitationRepository.findByTokenHash(presentedHash)
            .filter(inv -> constantTimeEquals(inv.getTokenHash(), presentedHash))
            .orElseThrow(() -> new InvalidInvitationException(GENERIC_ERROR));

        if (invitation.getRedeemedAt() != null
            || invitation.getExpiresAt().isBefore(Instant.now())
            || !invitation.getEmail().equalsIgnoreCase(email)) {
            throw new InvalidInvitationException(GENERIC_ERROR);
        }

        invitation.setRedeemedAt(Instant.now());
        invitationRepository.save(invitation);
        return invitation;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
