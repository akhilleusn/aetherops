package com.aetherops.incident.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class IncidentCreatedEventConsumer {

    private final ObjectMapper objectMapper;

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = ".DLT"
    )
    @KafkaListener(topics = IncidentEventPublisher.TOPIC, groupId = "aetherops")
    public void consume(String message,
                        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        try {
            IncidentCreatedEvent event = objectMapper.readValue(message, IncidentCreatedEvent.class);
            log.info("Received incident.created event for incidentId={} tenantId={} severity={} topic={}",
                    event.getIncidentId(), event.getTenantId(), event.getSeverity(), topic);

            // Future: trigger AI analysis, notifications, enrichment here

        } catch (Exception e) {
            log.error("Failed to process incident.created event from topic={}: {}", topic, message, e);
            throw new RuntimeException("Failed to process incident.created event", e);
        }
    }

    @KafkaListener(topics = IncidentEventPublisher.TOPIC + ".DLT", groupId = "aetherops-dlt")
    public void consumeDeadLetter(String message,
                                  @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("Dead letter received on topic={} message={}", topic, message);
        // Future: alert ops team, store in dead_letter_incidents table, trigger manual review
    }
}