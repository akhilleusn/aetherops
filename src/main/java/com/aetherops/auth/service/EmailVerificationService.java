package com.aetherops.auth.service;

import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.common.exception.InvalidVerificationTokenException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

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

        user.setEmailVerificationToken(token);
        user.setEmailVerificationTokenExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        userRepository.save(user);

        String link = dashboardBaseUrl + "/api/v1/auth/verify-email?token=" + token;
        emailService.sendVerificationEmail(user.getEmail(), link);
    }

    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByEmailVerificationToken(token)
                .orElseThrow(InvalidVerificationTokenException::new);

        if (Instant.now().isAfter(user.getEmailVerificationTokenExpiresAt())) {
            throw new InvalidVerificationTokenException();
        }

        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationTokenExpiresAt(null);
        userRepository.save(user);
    }
}
