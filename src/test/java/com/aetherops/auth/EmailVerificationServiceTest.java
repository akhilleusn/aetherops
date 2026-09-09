package com.aetherops.auth;

import com.aetherops.auth.model.AuthResponse;
import com.aetherops.auth.model.LoginRequest;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.auth.service.AuthService;
import com.aetherops.auth.service.EmailService;
import com.aetherops.auth.service.EmailVerificationService;
import com.aetherops.auth.service.InvitationService;
import com.aetherops.auth.service.JwtService;
import com.aetherops.auth.service.LoginAttemptService;
import com.aetherops.common.exception.InvalidVerificationTokenException;
import com.aetherops.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;

    @InjectMocks private EmailVerificationService emailVerificationService;

    @Test
    void verifyEmail_validToken_setsVerifiedAndClearsToken() {
        String token = "a".repeat(64);
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("alice@example.com")
                .role(Role.MEMBER)
                .tenantId(UUID.randomUUID())
                .emailVerified(false)
                .emailVerificationToken(token)
                .emailVerificationTokenExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        when(userRepository.findByEmailVerificationToken(token)).thenReturn(Optional.of(user));

        emailVerificationService.verifyEmail(token);

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getEmailVerificationToken()).isNull();
        assertThat(user.getEmailVerificationTokenExpiresAt()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void verifyEmail_expiredToken_throwsInvalidVerificationTokenException() {
        String token = "b".repeat(64);
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("bob@example.com")
                .role(Role.MEMBER)
                .tenantId(UUID.randomUUID())
                .emailVerified(false)
                .emailVerificationToken(token)
                .emailVerificationTokenExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();
        when(userRepository.findByEmailVerificationToken(token)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> emailVerificationService.verifyEmail(token))
                .isInstanceOf(InvalidVerificationTokenException.class)
                .hasMessageContaining("Invalid or expired verification token");
    }

    @Test
    void login_withEmailVerified_returnsToken() {
        // Verifies AuthService.login() succeeds for a verified user (no 403 thrown).
        UserRepository mockRepo = mock(UserRepository.class);
        BCryptPasswordEncoder mockEncoder = mock(BCryptPasswordEncoder.class);
        JwtService mockJwt = mock(JwtService.class);
        LoginAttemptService mockLas = mock(LoginAttemptService.class);
        EmailVerificationService mockEvs = mock(EmailVerificationService.class);
        TenantService mockTenantService = mock(TenantService.class);
        InvitationService mockInvitationService = mock(InvitationService.class);

        // login() never touches tenantService/invitationService — only register() does.
        AuthService authService = new AuthService(
                mockRepo, mockTenantService, mockInvitationService,
                mockEncoder, mockJwt, mockLas, mockEvs);

        String email = "verifieduser@example.com";
        String password = "correctpass";
        User demoUser = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash("hashed")
                .role(Role.MEMBER)
                .tenantId(UUID.randomUUID())
                .emailVerified(true)
                .twoFaEnabled(false)
                .build();

        when(mockRepo.findByEmail(email)).thenReturn(Optional.of(demoUser));
        when(mockEncoder.matches(password, "hashed")).thenReturn(true);
        when(mockJwt.generateToken(demoUser)).thenReturn("demo-jwt-token");
        when(mockJwt.getExpiresAt(anyInt())).thenReturn(Instant.now().plusSeconds(3600));
        when(mockJwt.getExpiryHours()).thenReturn(24);

        AuthResponse response = authService.login(new LoginRequest(email, password));

        assertThat(response.getToken()).isEqualTo("demo-jwt-token");
        assertThat(response.getEmail()).isEqualTo(email);
    }
}
