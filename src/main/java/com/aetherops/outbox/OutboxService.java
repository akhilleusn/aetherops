package com.aetherops.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    static final String OUTBOX_TOPIC = "outbox.events";

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public OutboxEvent record(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize outbox payload for type=" + eventType, e);
        }

        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payloadJson)
                .status("PENDING")
                .build();

        OutboxEvent saved = repository.save(event);
        log.info("Recorded outbox event id={} type={} aggregateId={}", saved.getId(), eventType, aggregateId);
        return saved;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = repository.findByStatusOrderByCreatedAtAsc("PENDING");
        if (pending.isEmpty()) return;

        log.debug("Relaying {} pending outbox events", pending.size());

        for (OutboxEvent event : pending) {
            try {
                String message = objectMapper.writeValueAsString(Map.of(
                        "eventType", event.getEventType(),
                        "aggregateType", event.getAggregateType(),
                        "aggregateId", event.getAggregateId().toString(),
                        "payload", event.getPayload()
                ));
                kafkaTemplate.send(OUTBOX_TOPIC, event.getAggregateId().toString(), message);
                event.setStatus("PUBLISHED");
                event.setPublishedAt(Instant.now());
                log.info("Relayed outbox event id={} type={}", event.getId(), event.getEventType());
            } catch (Exception e) {
                event.setStatus("FAILED");
                log.error("Failed to relay outbox event id={} type={}", event.getId(), event.getEventType(), e);
            }
            repository.save(event);
        }
    }
}
