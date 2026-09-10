package com.aetherops.webhook;

import com.aetherops.common.security.SsrfGuard;
import com.aetherops.config.FeatureFlag;
import com.aetherops.config.FeatureFlagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;
    private final FeatureFlagService featureFlagService;
    private final WebhookSignatureVerifier signatureVerifier;

    @Async
    public void deliver(UUID tenantId, String eventType, Object payload) {
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

            RestClient client = SsrfGuard.disableRedirects(restClientBuilder).build();
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
