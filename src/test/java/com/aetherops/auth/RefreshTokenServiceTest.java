package com.aetherops.auth;

import com.aetherops.auth.model.RefreshToken;
import com.aetherops.auth.repository.RefreshTokenRepository;
import com.aetherops.auth.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository repository;

    @InjectMocks
    private RefreshTokenService service;

    private UUID userId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
    }

    @Test
    void createRefreshToken_savesAndReturnsToken() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        RefreshToken token = service.createRefreshToken(userId, tenantId);

        assertThat(token.getUserId()).isEqualTo(userId);
        assertThat(token.getTenantId()).isEqualTo(tenantId);
        assertThat(token.getToken()).isNotBlank();
        assertThat(token.isRevoked()).isFalse();
        assertThat(token.getExpiresAt()).isAfter(Instant.now());
        verify(repository).save(any(RefreshToken.class));
    }

    @Test
    void validateRefreshToken_validToken_returnsToken() {
        RefreshToken token = RefreshToken.builder()
                .token("valid-token")
                .userId(userId)
                .tenantId(tenantId)
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(false)
                .build();
        when(repository.findByToken("valid-token")).thenReturn(Optional.of(token));

        RefreshToken result = service.validateRefreshToken("valid-token");

        assertThat(result.getToken()).isEqualTo("valid-token");
    }

    @Test
    void validateRefreshToken_revokedToken_throwsException() {
        RefreshToken token = RefreshToken.builder()
                .token("revoked-token")
                .userId(userId)
                .tenantId(tenantId)
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(true)
                .build();
        when(repository.findByToken("revoked-token")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.validateRefreshToken("revoked-token"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void validateRefreshToken_expiredToken_throwsException() {
        RefreshToken token = RefreshToken.builder()
                .token("expired-token")
                .userId(userId)
                .tenantId(tenantId)
                .expiresAt(Instant.now().minusSeconds(3600))
                .revoked(false)
                .build();
        when(repository.findByToken("expired-token")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.validateRefreshToken("expired-token"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void revokeAllUserTokens_deletesAllUserTokens() {
        service.revokeAllUserTokens(userId);
        verify(repository).deleteAllByUserId(userId);
    }
}