package com.aetherops.auth.service;

import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.common.exception.InvalidVerificationTokenException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * Phase 3 finding 2 (see STEP_REPORT.md): only the SHA-256 hash of the verification token is
 * ever persisted, the same pattern {@code PasswordResetService} already uses -- the raw token
 * exists only in the link emailed to the user and is never written to the database.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${app.dashboard-base-url:http://localhost:8080}")
    private String dashboardBaseUrl;

    @Transactional
    public void sendVerificationEmail(User user) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);

        user.setEmailVerificationTokenHash(sha256(token));
        user.setEmailVerificationTokenExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        userRepository.save(user);

        String link = dashboardBaseUrl + "/api/v1/auth/verify-email?token=" + token;
        emailService.sendVerificationEmail(user.getEmail(), link);
    }

    @Transactional
    public void resendVerificationEmail(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            if (!user.isEmailVerified()) {
                sendVerificationEmail(user);
            }
        });
    }

    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByEmailVerificationTokenHash(sha256(token))
                .orElseThrow(InvalidVerificationTokenException::new);

        if (Instant.now().isAfter(user.getEmailVerificationTokenExpiresAt())) {
            throw new InvalidVerificationTokenException();
        }

        user.setEmailVerified(true);
        user.setEmailVerificationTokenHash(null);
        user.setEmailVerificationTokenExpiresAt(null);
        userRepository.save(user);
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
