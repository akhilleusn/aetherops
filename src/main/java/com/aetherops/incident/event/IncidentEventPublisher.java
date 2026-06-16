package com.aetherops.incident.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class IncidentEventPublisher {

    public static final String TOPIC = "incident.created";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public IncidentEventPublisher(ObjectMapper objectMapper,
                                   @Nullable KafkaTemplate<String, String> kafkaTemplate) {
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(IncidentCreatedEvent event) {
        if (kafkaTemplate == null) {
            log.warn("Kafka not available; skipping incident.created event for incidentId={}", event.getIncidentId());
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(TOPIC, event.getIncidentId().toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to deliver incident.created event for incidentId={}",
                                event.getIncidentId(), ex);
                    }
                });
            log.info("Published incident.created event for incidentId={} tenantId={}",
                    event.getIncidentId(), event.getTenantId());
        } catch (Exception e) {
            log.error("Failed to publish incident.created event for incidentId={}",
                    event.getIncidentId(), e);
        }
    }
}