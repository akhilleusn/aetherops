package com.aetherops.auth;

import com.aetherops.auth.model.RefreshToken;
import com.aetherops.auth.repository.RefreshTokenRepository;
import com.aetherops.auth.service.RefreshTokenService;
import com.aetherops.common.exception.InvalidRefreshTokenException;
import com.aetherops.common.exception.RefreshTokenReuseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
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

    @Mock
    private com.aetherops.auth.service.RefreshTokenFamilyRevocationService familyRevocationService;

    @InjectMocks
    private RefreshTokenService service;

    private UUID userId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
    }

    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void issue_savesHashedTokenWithNewFamily_returnsRawToken() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        RefreshTokenService.IssuedToken issued = service.issue(userId, tenantId);

        assertThat(issued.rawToken()).isNotBlank();
        assertThat(issued.expiresAt()).isAfter(Instant.now());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        RefreshToken saved = captor.getValue();

        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getTokenHash()).isEqualTo(sha256(issued.rawToken()));
        // The raw token itself must never be persisted anywhere.
        assertThat(saved.getTokenHash()).isNotEqualTo(issued.rawToken());
        assertThat(saved.getFamilyId()).isNotNull();
        assertThat(saved.isUsed()).isFalse();
        assertThat(saved.isRevoked()).isFalse();
    }

    @Test
    void rotate_validToken_marksOldUsedAndIssuesNewInSameFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken existing = RefreshToken.builder()
                .tokenHash(sha256("valid-token"))
                .familyId(familyId)
                .userId(userId)
                .tenantId(tenantId)
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(false)
                .used(false)
                .build();
        when(repository.findByTokenHash(sha256("valid-token"))).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        RefreshTokenService.RotationResult result = service.rotate("valid-token");

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.next().rawToken()).isNotBlank();
        assertThat(result.next().rawToken()).isNotEqualTo("valid-token");
        assertThat(existing.isUsed()).isTrue();

        verify(repository, never()).revokeFamily(any());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, times(2)).save(captor.capture());
        RefreshToken newToken = captor.getAllValues().get(1);
        assertThat(newToken.getFamilyId()).isEqualTo(familyId);
    }

    @Test
    void rotate_alreadyUsedToken_revokesFamilyAndThrowsReuseException() {
        UUID familyId = UUID.randomUUID();
        RefreshToken reused = RefreshToken.builder()
                .tokenHash(sha256("reused-token"))
                .familyId(familyId)
                .userId(userId)
                .tenantId(tenantId)
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(false)
                .used(true)
                .build();
        when(repository.findByTokenHash(sha256("reused-token"))).thenReturn(Optional.of(reused));

        assertThatThrownBy(() -> service.rotate("reused-token"))
                .isInstanceOf(RefreshTokenReuseException.class);

        verify(familyRevocationService).revokeFamily(familyId);
        verify(repository, never()).save(any());
    }

    @Test
    void rotate_revokedToken_throwsInvalidRefreshTokenException() {
        RefreshToken token = RefreshToken.builder()
                .tokenHash(sha256("revoked-token"))
                .familyId(UUID.randomUUID())
                .userId(userId)
                .tenantId(tenantId)
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(true)
                .used(false)
                .build();
        when(repository.findByTokenHash(sha256("revoked-token"))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.rotate("revoked-token"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void rotate_expiredToken_throwsInvalidRefreshTokenException() {
        RefreshToken token = RefreshToken.builder()
                .tokenHash(sha256("expired-token"))
                .familyId(UUID.randomUUID())
                .userId(userId)
                .tenantId(tenantId)
                .expiresAt(Instant.now().minusSeconds(3600))
                .revoked(false)
                .used(false)
                .build();
        when(repository.findByTokenHash(sha256("expired-token"))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.rotate("expired-token"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rotate_unknownToken_throwsInvalidRefreshTokenException() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("unknown-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void revokeAllUserTokens_deletesAllUserTokens() {
        service.revokeAllUserTokens(userId);
        verify(repository).deleteAllByUserId(userId);
    }
}
