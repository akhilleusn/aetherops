package com.aetherops.auth.service;

import com.aetherops.auth.model.RegistrationResult;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.common.exception.DuplicateEmailException;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Trusted-tenantId registration for server-driven provisioning (see
 * TenantOnboardingService) only. Deliberately a separate bean from AuthService — which
 * every auth controller already holds a reference to — so no controller gains access to
 * this method merely by reusing an existing dependency. Only TenantOnboardingService should
 * ever be given this bean; a client can never reach it, since RegisterRequest's tenantId is
 * validation-rejected (see AuthController.register / AuthService.register).
 */
@Service
@RequiredArgsConstructor
public class InternalTenantProvisioningService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;

    @Transactional
    public RegistrationResult registerIntoTenant(String email, String password, UUID tenantId, Role role) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new ResourceNotFoundException("Tenant not found: " + tenantId);
        }
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
}
