package com.aetherops.integrations.webhook;

import com.aetherops.integrations.service.TicketIntegrationTokenEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers {@link WebhookSigningSecretService#backfillIfAbsent}, used only by
 * {@link LegacyWebhookSecretBackfillRunner} — the one property this method must have is never
 * clobbering a secret an operator (or the tenant themselves, once self-service exists) already
 * configured for real.
 */
@ExtendWith(MockitoExtension.class)
class WebhookSigningSecretServiceTest {

    @Mock WebhookSigningSecretRepository repository;
    @Mock TicketIntegrationTokenEncryptor encryptor;

    WebhookSigningSecretService service;

    @BeforeEach
    void setUp() {
        service = new WebhookSigningSecretService(repository, encryptor);
    }

    @Test
    void backfillIfAbsent_createsSecretWithGracePeriod_whenNoneExists() {
        UUID tenantId = UUID.randomUUID();
        Instant graceEndsAt = Instant.now().plusSeconds(3600);
        when(repository.findByTenantIdAndProviderAndEnabledTrue(tenantId, WebhookSigningProvider.DATADOG))
            .thenReturn(Optional.empty());
        when(encryptor.encrypt("plaintext-secret")).thenReturn("encrypted-value");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean created = service.backfillIfAbsent(tenantId, WebhookSigningProvider.DATADOG, "plaintext-secret", graceEndsAt);

        assertThat(created).isTrue();
        ArgumentCaptor<WebhookSigningSecret> captor = ArgumentCaptor.forClass(WebhookSigningSecret.class);
        verify(repository).save(captor.capture());
        WebhookSigningSecret saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getProvider()).isEqualTo(WebhookSigningProvider.DATADOG);
        assertThat(saved.getEncryptedSecret()).isEqualTo("encrypted-value");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getGracePeriodEndsAt()).isEqualTo(graceEndsAt);
    }

    @Test
    void backfillIfAbsent_doesNotOverwriteAnAlreadyConfiguredSecret() {
        UUID tenantId = UUID.randomUUID();
        WebhookSigningSecret existing = WebhookSigningSecret.builder()
            .tenantId(tenantId).provider(WebhookSigningProvider.DATADOG)
            .encryptedSecret("operator-set-value").enabled(true).build();
        when(repository.findByTenantIdAndProviderAndEnabledTrue(tenantId, WebhookSigningProvider.DATADOG))
            .thenReturn(Optional.of(existing));

        boolean created = service.backfillIfAbsent(tenantId, WebhookSigningProvider.DATADOG, "new-plaintext-secret",
            Instant.now().plusSeconds(3600));

        assertThat(created).isFalse();
        verify(repository, never()).save(any());
        verify(encryptor, never()).encrypt(any());
    }
}
