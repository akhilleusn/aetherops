package com.aetherops.integrations.webhook;

import com.aetherops.integrations.service.TicketIntegrationTokenEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Looks up a tenant's opt-in signing secret for a webhook provider. The lookup must run
 * inside a real {@code @Transactional} method (not a bare repository call) so
 * TenantContextInterceptor reliably applies {@code app.tenant_id} for this RLS-protected
 * table — see AbstractIntegrationTest's withTenant() javadoc for the same caveat.
 *
 * Reuses TicketIntegrationTokenEncryptor (AES/GCM, app.integrations.token-encryption-key)
 * rather than adding a new encryptor/env-var — this table lives in the same
 * com.aetherops.integrations domain that encryptor already exists to protect secrets for.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookSigningSecretService {

    private final WebhookSigningSecretRepository repository;
    private final TicketIntegrationTokenEncryptor encryptor;

    @Transactional(readOnly = true)
    public Optional<WebhookSigningSecret> find(UUID tenantId, WebhookSigningProvider provider) {
        return repository.findByTenantIdAndProviderAndEnabledTrue(tenantId, provider);
    }

    public String decrypt(WebhookSigningSecret secret) {
        return encryptor.decrypt(secret.getEncryptedSecret());
    }

    @Transactional
    public WebhookSigningSecret configure(UUID tenantId, WebhookSigningProvider provider, String plaintextSecret,
                                           String headerName, String timestampHeaderName) {
        WebhookSigningSecret secret = repository.findByTenantIdAndProviderAndEnabledTrue(tenantId, provider)
            .orElseGet(() -> WebhookSigningSecret.builder().tenantId(tenantId).provider(provider).build());
        secret.setEncryptedSecret(encryptor.encrypt(plaintextSecret));
        secret.setHeaderName(headerName);
        secret.setTimestampHeaderName(timestampHeaderName);
        secret.setEnabled(true);
        WebhookSigningSecret saved = repository.save(secret);
        log.info("Configured {} webhook signing secret for tenantId={}", provider, tenantId);
        return saved;
    }
}
