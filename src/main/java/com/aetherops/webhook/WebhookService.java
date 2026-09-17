package com.aetherops.webhook;

import com.aetherops.common.security.SsrfGuard;
import com.aetherops.config.FeatureFlag;
import com.aetherops.config.FeatureFlagService;
import com.aetherops.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class WebhookService {

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;
    private final FeatureFlagService featureFlagService;
    private final WebhookSignatureVerifier signatureVerifier;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public WebhookService(
            WebhookEndpointRepository endpointRepository,
            WebhookDeliveryRepository deliveryRepository,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            FeatureFlagService featureFlagService,
            WebhookSignatureVerifier signatureVerifier,
            @Value("${app.webhooks.delivery-connect-timeout-ms}") long connectTimeoutMs,
            @Value("${app.webhooks.delivery-read-timeout-ms}") long readTimeoutMs) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
        this.restClientBuilder = restClientBuilder;
        this.featureFlagService = featureFlagService;
        this.signatureVerifier = signatureVerifier;
        this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
    }

    @Async("webhookExecutor")
    public void deliver(UUID tenantId, String eventType, Object payload) {
        // Step H2 (see STEP_REPORT.md): bug found while implementing, not part of the original
        // finding -- this method was already @Async (on the shared default pool) before this
        // change, dispatched from a caller (e.g. IncidentService) that has TenantContext set on
        // ITS OWN thread. TenantContext is a plain ThreadLocal (see TenantContext.java) with no
        // propagation across an executor boundary, so it was never actually set on whatever
        // thread ran this method's body -- meaning every RLS-protected read/write below
        // (endpointRepository, deliveryRepository) ran with app.tenant_id unset. Per the RLS
        // policies (see V28/V60 migrations), an unset app.tenant_id makes tenant_id = NULL,
        // which never matches -- reads silently return zero rows and writes fail RLS's check.
        // Traced through the code and the RLS policy SQL, not observed against a live database
        // (Docker/Testcontainers unavailable in this session, same as every prior step) -- see
        // STEP_REPORT.md for why this is reported as a real, separate bug rather than assumed.
        TenantContext.set(tenantId);
        try {
            deliverInternal(tenantId, eventType, payload);
        } finally {
            TenantContext.clear();
        }
    }

    private void deliverInternal(UUID tenantId, String eventType, Object payload) {
        if (!featureFlagService.isEnabled(FeatureFlag.WEBHOOKS, tenantId)) {
            log.info("WEBHOOKS feature flag disabled for tenantId={}", tenantId);
            return;
        }

        List<WebhookEndpoint> endpoints = endpointRepository
                .findByTenantIdAndActiveTrue(tenantId);

        if (endpoints.isEmpty()) {
            return;
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize webhook payload for tenantId={}", tenantId, e);
            return;
        }

        for (WebhookEndpoint endpoint : endpoints) {
            WebhookDelivery delivery = deliveryRepository.save(WebhookDelivery.builder()
                    .webhookEndpointId(endpoint.getId())
                    .eventType(eventType)
                    .payload(payloadJson)
                    .status("PENDING")
                    .attempts(0)
                    .build());

            attemptDelivery(endpoint, delivery);
        }
    }

    private void attemptDelivery(WebhookEndpoint endpoint, WebhookDelivery delivery) {
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastAttemptAt(Instant.now());

        try {
            // Re-validate at delivery time, not just at registration time -- DNS for an
            // already-accepted url can be repointed after it was registered. See
            // SsrfGuard's javadoc.
            SsrfGuard.validate(endpoint.getUrl());

            String signature = signatureVerifier.computeHmacSha256(endpoint.getSecret(), delivery.getPayload());

            RestClient client = SsrfGuard.disableRedirects(restClientBuilder, connectTimeout, readTimeout).build();
            ResponseEntity<Void> response = client.post()
                    .uri(endpoint.getUrl())
                    .header("Content-Type", "application/json")
                    .header("X-AetherOps-Event", delivery.getEventType())
                    .header("X-AetherOps-Signature", signature)
                    .body(delivery.getPayload())
                    .retrieve()
                    .toBodilessEntity();

            if (response.getStatusCode().is3xxRedirection()) {
                throw new IllegalStateException("Webhook endpoint returned a redirect — redirects are not followed");
            }

            delivery.setStatus("SUCCESS");
            delivery.setResponseStatus(response.getStatusCode().value());
            log.info("Webhook delivered endpointId={} eventType={}",
                    endpoint.getId(), delivery.getEventType());

        } catch (Exception e) {
            delivery.setStatus(delivery.getAttempts() >= 3 ? "FAILED" : "RETRYING");
            delivery.setResponseBody(e.getMessage());
            delivery.setNextRetryAt(Instant.now().plusSeconds(60L * delivery.getAttempts()));
            log.warn("Webhook delivery failed endpointId={} attempt={} error={}",
                    endpoint.getId(), delivery.getAttempts(), e.getMessage());
        }

        deliveryRepository.save(delivery);
    }

    public WebhookEndpoint register(UUID tenantId, String url, String secret) {
        // Unreachable via any controller today (see STEP_REPORT.md) -- validating here, not
        // only at delivery time, means the moment a controller does call this, it's already
        // safe with no further change needed.
        SsrfGuard.validate(url);

        if (secret == null || secret.isBlank()) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            secret = HexFormat.of().formatHex(bytes);
            log.info("Generated random secret for webhook endpoint tenantId={}", tenantId);
        }
        return endpointRepository.save(WebhookEndpoint.builder()
                .tenantId(tenantId)
                .url(url)
                .secret(secret)
                .active(true)
                .build());
    }

    public void deactivate(UUID endpointId) {
        endpointRepository.findById(endpointId).ifPresent(ep -> {
            ep.setActive(false);
            endpointRepository.save(ep);
        });
    }
}
