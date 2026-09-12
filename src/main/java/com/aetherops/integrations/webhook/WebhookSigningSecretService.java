package com.aetherops.integrations.webhook;

import com.aetherops.integrations.service.TicketIntegrationTokenEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Looks up a tenant's opt-in signing secret for a webhook provider. The lookup must run
 * inside a real {@code @Transactional} method (not a bare repository call) so
 * TenantContextInterceptor reliably applies {@code app.tenant_id} for this RLS-protected
 * table — see AbstractIntegrationTest's withTenant() javadoc for the same caveat.
 *
 * Reuses TicketIntegrationTokenEncryptor (versioned AES/GCM,
 * app.integrations.token-encryption) rather than adding a new encryptor/env-var — this table
 * lives in the same com.aetherops.integrations domain that encryptor already exists to protect
 * secrets for.
 *
 * <p><b>Trust-domain coupling this creates:</b> inbound webhook signing secrets (GitHub/
 * PagerDuty/Grafana) and outbound Jira/ServiceNow API tokens are two independent trust domains
 * sharing one key/version space purely as an implementation convenience. Rotating
 * app.integrations.token-encryption rotates both simultaneously — there is no way to give
 * webhook signing secrets their own key version numbering without giving them independent key
 * material (i.e. splitting this into its own encryptor), which is a separate change, not part
 * of Phase 3 finding 1. See STEP_REPORT.md.
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

    /**
     * Used only by {@code LegacyWebhookSecretBackfillRunner} to generate a secret for an
     * existing tenant that has none yet, without disturbing one that already exists — an
     * operator who already configured a real secret for (tenantId, provider) must never have
     * it silently overwritten by a re-run of a startup migration. Returns false (no-op) if a
     * row already exists, true if one was created.
     */
    @Transactional
    public boolean backfillIfAbsent(UUID tenantId, WebhookSigningProvider provider, String plaintextSecret,
                                     Instant gracePeriodEndsAt) {
        if (repository.findByTenantIdAndProviderAndEnabledTrue(tenantId, provider).isPresent()) {
            return false;
        }
        WebhookSigningSecret secret = WebhookSigningSecret.builder()
            .tenantId(tenantId)
            .provider(provider)
            .encryptedSecret(encryptor.encrypt(plaintextSecret))
            .enabled(true)
            .gracePeriodEndsAt(gracePeriodEndsAt)
            .build();
        repository.save(secret);
        return true;
    }
}
