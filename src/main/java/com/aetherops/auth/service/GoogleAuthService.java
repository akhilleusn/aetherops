package com.aetherops.auth.service;

import com.aetherops.auth.model.AuthResponse;
import com.aetherops.auth.model.GoogleAuthRequest;
import com.aetherops.auth.model.GoogleUserInfo;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.common.exception.MissingTenantIdException;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    private final GoogleTokenVerifier tokenVerifier;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final AuthService authService;

    @Transactional
    public AuthResponse authenticate(GoogleAuthRequest request) {
        GoogleUserInfo userInfo = tokenVerifier.verify(request.idToken());

        Optional<User> byGoogleSub = userRepository.findByGoogleSub(userInfo.sub());
        if (byGoogleSub.isPresent()) {
            return authService.issueResponse(byGoogleSub.get());
        }

        Optional<User> byEmail = userRepository.findByEmail(userInfo.email());
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            user.setGoogleSub(userInfo.sub());
            userRepository.save(user);
            return authService.issueResponse(user);
        }

        // New user — tenantId required
        if (request.tenantId() == null) {
            throw new MissingTenantIdException();
        }
        if (!tenantRepository.existsById(request.tenantId())) {
            throw new ResourceNotFoundException("Tenant not found: " + request.tenantId());
        }

        User newUser = userRepository.save(User.builder()
            .email(userInfo.email())
            .googleSub(userInfo.sub())
            .role(Role.MEMBER)
            .tenantId(request.tenantId())
            .emailVerified(true)
            .build());

        return authService.issueResponse(newUser);
    }
}
