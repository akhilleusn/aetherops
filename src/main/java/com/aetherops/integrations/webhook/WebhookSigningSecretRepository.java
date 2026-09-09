package com.aetherops.integrations.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookSigningSecretRepository extends JpaRepository<WebhookSigningSecret, UUID> {
    Optional<WebhookSigningSecret> findByTenantIdAndProviderAndEnabledTrue(UUID tenantId, WebhookSigningProvider provider);
}
