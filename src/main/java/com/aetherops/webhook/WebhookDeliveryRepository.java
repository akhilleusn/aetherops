package com.aetherops.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findByWebhookEndpointIdAndStatus(UUID webhookEndpointId, String status);
}