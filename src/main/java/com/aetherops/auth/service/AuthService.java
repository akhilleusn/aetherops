package com.aetherops.auth.service;

import com.aetherops.auth.model.AuthResponse;
import com.aetherops.auth.model.Invitation;
import com.aetherops.auth.model.LoginRequest;
import com.aetherops.auth.model.RegisterRequest;
import com.aetherops.auth.model.RegistrationResult;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.common.exception.AccountLockedException;
import com.aetherops.common.exception.DuplicateEmailException;
import com.aetherops.common.exception.EmailNotVerifiedException;
import com.aetherops.common.exception.InvalidCredentialsException;
import com.aetherops.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final InvitationService invitationService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final EmailVerificationService emailVerificationService;

    /**
     * The caller never chooses which tenant they join (see RegisterRequest). Registration is by
     * invitation only -- {@code invitationToken} is required ({@code @NotBlank} on
     * {@link RegisterRequest}), so this always binds to the tenant the invitation names.
     * Creating a brand-new tenant is a separate, authenticated action via
     * {@code POST /api/v1/tenants} -- not something an unauthenticated registration triggers.
     */
    @Transactional
    public RegistrationResult register(RegisterRequest req) {
        Invitation invitation = invitationService.redeem(req.invitationToken(), req.email());
        return registerUser(req.email(), req.password(), invitation.getTenantId(), invitation.getRole());
    }

    private RegistrationResult registerUser(String email, String password, UUID tenantId, Role role) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new DuplicateEmailException(email);
        }
        User user = User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(password))
            .role(role)
            .tenantId(tenantId)
            .emailVerified(false)
            .build();
        userRepository.save(user);
        emailVerificationService.sendVerificationEmail(user);
        return new RegistrationResult(user.getId(),
            "Registration successful. Please check your email to verify your account.");
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        if (loginAttemptService.isLocked(req.email())) {
            throw new AccountLockedException(loginAttemptService.minutesUntilUnlock(req.email()));
        }
        User user = userRepository.findByEmail(req.email())
            .orElseThrow(InvalidCredentialsException::new);
        if (user.getPasswordHash() == null) {
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            loginAttemptService.recordFailure(req.email());
            throw new InvalidCredentialsException();
        }
        loginAttemptService.recordSuccess(req.email());
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        return issueResponse(user);
    }

    public AuthResponse issueResponse(User user) {
        if (user.isTwoFaEnabled()) {
            return AuthResponse.builder()
                .requiresTwoFactor(true)
                .partialToken(jwtService.generatePartialToken(user))
                .build();
        }
        return buildResponse(user);
    }

    public AuthResponse buildResponse(User user) {
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

    public String generateTokenForUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        return jwtService.generateToken(user);
    }

}
