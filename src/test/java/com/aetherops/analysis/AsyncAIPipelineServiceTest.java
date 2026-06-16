package com.aetherops.analysis;

import com.aetherops.analysis.model.AIAnalysisResponse;
import com.aetherops.analysis.service.AIAnalysisService;
import com.aetherops.analysis.service.AsyncAIPipelineService;
import com.aetherops.incident.event.IncidentCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncAIPipelineServiceTest {

    @Mock private AIAnalysisService aiAnalysisService;
    @InjectMocks private AsyncAIPipelineService service;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
    }

    @Test
    void onIncidentCreated_triggersAnalysis() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        IncidentCreatedEvent event = IncidentCreatedEvent.builder()
                .incidentId(incidentId).tenantId(tenantId).build();
        String message = objectMapper.writeValueAsString(event);
        when(aiAnalysisService.analyze(incidentId, tenantId))
                .thenReturn(AIAnalysisResponse.builder()
                        .incidentId(incidentId).tenantId(tenantId).build());

        service.onIncidentCreated(message);

        verify(aiAnalysisService).analyze(incidentId, tenantId);
    }

    @Test
    void onIncidentCreated_doesNotThrow_whenAnalysisFails() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        IncidentCreatedEvent event = IncidentCreatedEvent.builder()
                .incidentId(incidentId).tenantId(tenantId).build();
        String message = objectMapper.writeValueAsString(event);
        when(aiAnalysisService.analyze(any(), any())).thenThrow(new RuntimeException("AI unavailable"));

        service.onIncidentCreated(message);

        verify(aiAnalysisService).analyze(incidentId, tenantId);
    }

    @Test
    void onIncidentCreated_doesNotThrow_onInvalidJson() {
        service.onIncidentCreated("not-valid-json");

        verify(aiAnalysisService, never()).analyze(any(), any());
    }
}
