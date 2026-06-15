package com.aetherops.incident.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class IncidentEventPublisher {

    public static final String TOPIC = "incident.created";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publish(IncidentCreatedEvent event) {
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