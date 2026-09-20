package com.aetherops.auth.service;

import com.aetherops.auth.model.AuthResponse;
import com.aetherops.auth.model.GoogleAuthRequest;
import com.aetherops.auth.model.GoogleUserInfo;
import com.aetherops.auth.model.Invitation;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.common.exception.InvalidInvitationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    private final GoogleTokenVerifier tokenVerifier;
    private final UserRepository userRepository;
    private final InvitationService invitationService;
    private final AuthService authService;

    @Transactional
    public AuthResponse authenticate(GoogleAuthRequest request) {
        GoogleUserInfo userInfo = tokenVerifier.verify(request.idToken());

        Optional<User> byGoogleSub = userRepository.findByGoogleSub(userInfo.sub());
        if (byGoogleSub.isPresent()) {
            return authService.issueResponse(byGoogleSub.get());
        }

        // Email match is a Google-verified identity claim, not client input — linking here
        // only ever attaches this Google account to the SAME existing user row (and thus
        // their existing tenant); it never lets the caller choose or change a tenant.
        Optional<User> byEmail = userRepository.findByEmail(userInfo.email());
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            user.setGoogleSub(userInfo.sub());
            userRepository.save(user);
            return authService.issueResponse(user);
        }

        // New user — the caller never chooses which tenant they join (see GoogleAuthRequest).
        // This endpoint doubles as login and first-time registration, so invitationToken can't
        // be @NotBlank on the DTO the way RegisterRequest's is (existing users must be able to
        // omit it) -- the requirement is enforced here instead, only once we know this is
        // actually a brand-new user. Registration is by invitation only, same rule as
        // AuthService.register: no invitation means no new tenant gets created.
        if (request.invitationToken() == null || request.invitationToken().isBlank()) {
            throw new InvalidInvitationException("Registration requires an invitation");
        }
        Invitation invitation = invitationService.redeem(request.invitationToken(), userInfo.email());

        User newUser = userRepository.save(User.builder()
            .email(userInfo.email())
            .googleSub(userInfo.sub())
            .role(invitation.getRole())
            .tenantId(invitation.getTenantId())
            .emailVerified(true)
            .build());

        return authService.issueResponse(newUser);
    }
}
