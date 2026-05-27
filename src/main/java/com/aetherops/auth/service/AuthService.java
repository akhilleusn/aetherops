package com.aetherops.auth.service;

import com.aetherops.auth.model.AuthResponse;
import com.aetherops.auth.model.LoginRequest;
import com.aetherops.auth.model.RegisterRequest;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.common.exception.AccountLockedException;
import com.aetherops.common.exception.DuplicateEmailException;
import com.aetherops.common.exception.InvalidCredentialsException;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (!tenantRepository.existsById(req.tenantId())) {
            throw new ResourceNotFoundException("Tenant not found: " + req.tenantId());
        }
        if (userRepository.findByEmail(req.email()).isPresent()) {
            throw new DuplicateEmailException(req.email());
        }
        User user = User.builder()
            .email(req.email())
            .passwordHash(passwordEncoder.encode(req.password()))
            .role(Role.MEMBER)
            .tenantId(req.tenantId())
            .build();
        userRepository.save(user);
        return buildResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        if (loginAttemptService.isLocked(req.email())) {
            throw new AccountLockedException(loginAttemptService.minutesUntilUnlock(req.email()));
        }
        User user = userRepository.findByEmail(req.email())
            .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            loginAttemptService.recordFailure(req.email());
            throw new InvalidCredentialsException();
        }
        loginAttemptService.recordSuccess(req.email());
        return buildResponse(user);
    }

    private AuthResponse buildResponse(User user) {
        String token = jwtService.generateToken(user);
        return AuthResponse.builder()
            .token(token)
            .expiresAt(jwtService.getExpiresAt(jwtService.getExpiryHours()))
            .userId(user.getId())
            .tenantId(user.getTenantId())
            .email(user.getEmail())
            .role(user.getRole().name())
            .build();
    }
}
