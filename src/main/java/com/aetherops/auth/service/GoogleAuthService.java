package com.aetherops.auth.service;

import com.aetherops.auth.model.AuthResponse;
import com.aetherops.auth.model.GoogleAuthRequest;
import com.aetherops.auth.model.GoogleUserInfo;
import com.aetherops.auth.model.Invitation;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.TenantResponse;
import com.aetherops.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    private final GoogleTokenVerifier tokenVerifier;
    private final UserRepository userRepository;
    private final TenantService tenantService;
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
        // An invitationToken binds this registration to the tenant it was issued for;
        // without one, registration always creates a brand-new tenant with this user as OWNER.
        UUID tenantId;
        Role role;
        if (request.invitationToken() != null && !request.invitationToken().isBlank()) {
            Invitation invitation = invitationService.redeem(request.invitationToken(), userInfo.email());
            tenantId = invitation.getTenantId();
            role = invitation.getRole();
        } else {
            TenantResponse tenant = tenantService.create(
                new CreateTenantRequest(defaultTenantName(userInfo.email()), 30));
            tenantId = tenant.getId();
            role = Role.OWNER;
        }

        User newUser = userRepository.save(User.builder()
            .email(userInfo.email())
            .googleSub(userInfo.sub())
            .role(role)
            .tenantId(tenantId)
            .emailVerified(true)
            .build());

        return authService.issueResponse(newUser);
    }

    private static String defaultTenantName(String email) {
        int at = email.indexOf('@');
        String localPart = at > 0 ? email.substring(0, at) : email;
        String name = localPart + "'s Team";
        return name.length() > 100 ? name.substring(0, 100) : name;
    }
}
