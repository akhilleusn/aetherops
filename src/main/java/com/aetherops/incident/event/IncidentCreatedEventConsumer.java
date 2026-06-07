package com.aetherops.incident.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class IncidentCreatedEventConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = IncidentEventPublisher.TOPIC, groupId = "aetherops")
    public void consume(String message) {
        try {
            IncidentCreatedEvent event = objectMapper.readValue(message, IncidentCreatedEvent.class);
            log.info("Received incident.created event for incidentId={} tenantId={} severity={}",
                    event.getIncidentId(), event.getTenantId(), event.getSeverity());

            // Future: trigger AI analysis, notifications, enrichment here
        } catch (Exception e) {
            log.error("Failed to process incident.created event: {}", message, e);
        }
    }
}