package com.aetherops.analysis.service;

import com.aetherops.incident.event.IncidentCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
@RequiredArgsConstructor
@Slf4j
public class AsyncAIPipelineService {

    private final AIAnalysisService aiAnalysisService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "incident.created", groupId = "aetherops-ai-pipeline")
    public void onIncidentCreated(String message) {
        IncidentCreatedEvent event = null;
        try {
            event = objectMapper.readValue(message, IncidentCreatedEvent.class);
            UUID incidentId = event.getIncidentId();
            UUID tenantId   = event.getTenantId();

            log.info("AsyncAIPipeline: triggering analysis for incidentId={} tenantId={}", incidentId, tenantId);
            aiAnalysisService.analyze(incidentId, tenantId);
            log.info("AsyncAIPipeline: analysis complete for incidentId={}", incidentId);

        } catch (Exception e) {
            UUID incidentId = event != null ? event.getIncidentId() : null;
            log.error("AsyncAIPipeline: failed to process incidentId={} message={}: {}",
                    incidentId, message, e.getMessage(), e);
        }
    }
}
