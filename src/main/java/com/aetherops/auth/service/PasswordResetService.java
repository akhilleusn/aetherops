package com.aetherops.auth.service;

import com.aetherops.auth.model.PasswordResetToken;
import com.aetherops.auth.repository.PasswordResetTokenRepository;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.auth.model.User;
import com.aetherops.common.exception.InvalidResetTokenException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Value("${app.dashboard-base-url:http://localhost:8080}")
    private String dashboardBaseUrl;

    @Transactional
    public void requestReset(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) return; // never reveal whether email exists

        tokenRepository.deleteByUserId(userOpt.get().getId());

        String rawToken = UUID.randomUUID().toString();
        String tokenHash = sha256(rawToken);

        tokenRepository.save(PasswordResetToken.builder()
            .userId(userOpt.get().getId())
            .tokenHash(tokenHash)
            .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
            .used(false)
            .build());

        String link = dashboardBaseUrl + "/reset-password.html?token=" + rawToken;
        emailService.sendPasswordResetEmail(email, link);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = sha256(rawToken);

        PasswordResetToken token = tokenRepository.findByTokenHash(tokenHash)
            .orElseThrow(InvalidResetTokenException::new);

        if (token.isUsed()) throw new InvalidResetTokenException();
        if (Instant.now().isAfter(token.getExpiresAt())) throw new InvalidResetTokenException();

        User user = userRepository.findById(token.getUserId())
            .orElseThrow(InvalidResetTokenException::new);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsed(true);
        tokenRepository.save(token);
    }

    private String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
