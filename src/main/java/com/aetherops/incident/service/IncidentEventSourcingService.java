package com.aetherops.incident.service;

import com.aetherops.incident.model.IncidentEventRecord;
import com.aetherops.incident.repository.IncidentEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentEventSourcingService {

    private final IncidentEventRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public IncidentEventRecord appendEvent(UUID incidentId, UUID tenantId, String eventType, Object payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize event payload for type=" + eventType, e);
        }

        long nextSeq = repository.findMaxSequenceByIncidentId(incidentId) + 1;

        IncidentEventRecord record = IncidentEventRecord.builder()
                .incidentId(incidentId)
                .tenantId(tenantId)
                .eventType(eventType)
                .payload(payloadJson)
                .sequence(nextSeq)
                .occurredAt(Instant.now())
                .build();

        IncidentEventRecord saved = repository.save(record);
        log.info("Appended event eventType={} incidentId={} sequence={}", eventType, incidentId, nextSeq);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<IncidentEventRecord> getEvents(UUID incidentId) {
        return repository.findByIncidentIdOrderBySequenceAsc(incidentId);
    }
}
