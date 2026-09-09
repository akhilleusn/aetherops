package com.aetherops.auth.service;

import com.aetherops.auth.model.AuthResponse;
import com.aetherops.auth.model.TotpBackupCode;
import com.aetherops.auth.model.TwoFaSetupResponse;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.TotpBackupCodeRepository;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.common.exception.InvalidCredentialsException;
import com.aetherops.common.exception.InvalidTotpCodeException;
import com.aetherops.common.exception.TwoFaAlreadyEnabledException;
import com.aetherops.common.exception.TwoFaNotEnabledException;
import com.aetherops.common.exception.TwoFaSessionLockedException;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TotpService {

    private static final int BACKUP_CODE_COUNT = 8;
    private static final String BACKUP_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int BACKUP_CODE_LENGTH = 8;

    private final UserRepository userRepository;
    private final TotpBackupCodeRepository backupCodeRepository;
    private final TotpSecretEncryptor encryptor;
    private final JwtService jwtService;
    private final AuthService authService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final TwoFactorAttemptService twoFactorAttemptService;

    private final CodeVerifier codeVerifier = buildCodeVerifier();

    @Transactional
    public TwoFaSetupResponse setup(String email) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

        String rawSecret = new DefaultSecretGenerator().generate();
        user.setTotpSecret(encryptor.encrypt(rawSecret));
        userRepository.save(user);

        backupCodeRepository.deleteByUserId(user.getId());
        List<String> plainCodes = new ArrayList<>();
        for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
            String code = generateBackupCode();
            plainCodes.add(code);
            backupCodeRepository.save(TotpBackupCode.builder()
                .userId(user.getId())
                .codeHash(sha256(code))
                .used(false)
                .build());
        }

        String qrCodeUrl = "otpauth://totp/AetherOps:" + email
            + "?secret=" + rawSecret
            + "&issuer=AetherOps"
            + "&algorithm=SHA1"
            + "&digits=6"
            + "&period=30";

        return new TwoFaSetupResponse(qrCodeUrl, rawSecret, plainCodes);
    }

    @Transactional
    public void verifySetup(String email, String code) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isTwoFaEnabled()) {
            throw new TwoFaAlreadyEnabledException();
        }
        if (user.getTotpSecret() == null) {
            throw new InvalidTotpCodeException();
        }

        String rawSecret = encryptor.decrypt(user.getTotpSecret());
        if (!codeVerifier.isValidCode(rawSecret, code)) {
            throw new InvalidTotpCodeException();
        }

        user.setTwoFaEnabled(true);
        userRepository.save(user);
    }

    @Transactional
    public AuthResponse verifyAndIssueToken(String partialToken, String code) {
        if (!jwtService.isTokenValid(partialToken) || !jwtService.isPartialToken(partialToken)) {
            throw new InvalidTotpCodeException();
        }

        // Checked before touching the code at all: once this session has hit the failure
        // limit, no code — right or wrong — should succeed. The only way out is a fresh
        // login, which mints a new partial token and thus a new counter.
        if (twoFactorAttemptService.isLocked(partialToken)) {
            throw new TwoFaSessionLockedException();
        }

        UUID userId = jwtService.extractUserId(partialToken);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new InvalidTotpCodeException());

        String rawSecret = encryptor.decrypt(user.getTotpSecret());
        if (codeVerifier.isValidCode(rawSecret, code)) {
            return authService.buildResponse(user);
        }

        String codeHash = sha256(code.toUpperCase());
        List<TotpBackupCode> unusedCodes = backupCodeRepository.findByUserIdAndUsedFalse(userId);
        for (TotpBackupCode backup : unusedCodes) {
            if (backup.getCodeHash().equals(codeHash)) {
                backup.setUsed(true);
                backupCodeRepository.save(backup);
                return authService.buildResponse(user);
            }
        }

        twoFactorAttemptService.recordFailure(partialToken);
        throw new InvalidTotpCodeException();
    }

    @Transactional
    public void disable(String email, String password) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        if (!user.isTwoFaEnabled()) {
            throw new TwoFaNotEnabledException();
        }

        user.setTwoFaEnabled(false);
        user.setTotpSecret(null);
        userRepository.save(user);
        backupCodeRepository.deleteByUserId(user.getId());
    }

    private String generateBackupCode() {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(BACKUP_CODE_LENGTH);
        for (int i = 0; i < BACKUP_CODE_LENGTH; i++) {
            sb.append(BACKUP_CODE_CHARS.charAt(rng.nextInt(BACKUP_CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static CodeVerifier buildCodeVerifier() {
        DefaultCodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(generator, new SystemTimeProvider());
        verifier.setAllowedTimePeriodDiscrepancy(1);
        return verifier;
    }
}
