package com.aetherops.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
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

    @Async
    public void deliver(UUID tenantId, String eventType, Object payload) {
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
            RestClient client = restClientBuilder.build();
            var response = client.post()
                    .uri(endpoint.getUrl())
                    .header("Content-Type", "application/json")
                    .header("X-AetherOps-Event", delivery.getEventType())
                    .body(delivery.getPayload())
                    .retrieve()
                    .toBodilessEntity();

            delivery.setStatus("SUCCESS");
            delivery.setResponseStatus(response.getStatusCode().value());
            log.info("Webhook delivered successfully endpointId={} eventType={}",
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