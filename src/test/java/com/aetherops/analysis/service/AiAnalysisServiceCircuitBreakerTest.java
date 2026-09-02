package com.aetherops.analysis.service;

import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.analysis.model.AIAnalysisResponse;
import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.audit.service.AuditLogService;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.evidence.service.EvidencePackService;
import com.aetherops.usage.service.UsageLimitService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.aetherops.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.aetherops.analysis.model.AnalysisEvaluationResult;


@ActiveProfiles("test")
@TestPropertySource(properties = {
        "resilience4j.circuitbreaker.instances.anthropic-ai.sliding-window-size=5",
        "resilience4j.circuitbreaker.instances.anthropic-ai.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.anthropic-ai.wait-duration-in-open-state=60s"
})
class AIAnalysisServiceCircuitBreakerTest extends AbstractIntegrationTest {

    @Autowired
    private AIAnalysisService aiAnalysisService;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoBean
    private AIProvider aiProvider;

    @MockitoBean
    private EvidencePackService evidencePackService;

    @MockitoBean
    private AIAnalysisRepository aiAnalysisRepository;

    @MockitoBean
    private UsageLimitService usageLimitService;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private AnalysisEvaluationService evaluationService;

    private UUID incidentId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        incidentId = UUID.randomUUID();
        tenantId = UUID.randomUUID();

        circuitBreakerRegistry.circuitBreaker("anthropic-ai").reset();

        IncidentEvidencePackResponse pack = IncidentEvidencePackResponse.builder()
                .incidentId(incidentId)
                .tenantId(tenantId)
                .evidence(List.of())
                .totalEvidenceCount(0)
                .build();

        when(evidencePackService.getEvidencePack(any(), any())).thenReturn(pack);
        when(aiAnalysisRepository.countByIncidentId(any())).thenReturn(0L);
        when(aiAnalysisRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(evaluationService.evaluate(any(), any())).thenReturn(
                AnalysisEvaluationResult.builder().passed(true).checksPerformed(1).build()
        );
    }

    @Test
    void whenAiProviderFails_fallbackResponseIsReturned() {
        when(aiProvider.analyze(any(AIAnalysisContext.class)))
                .thenThrow(new RuntimeException("Anthropic API unavailable"));

        AIAnalysisResponse response = aiAnalysisService.analyze(incidentId, tenantId);

        assertThat(response.getSummary()).contains("temporarily unavailable");
        assertThat(response.getConfidenceScore()).isEqualTo(0.0);
    }

    @Test
    void whenCircuitIsOpen_fallbackReturnedImmediately() {
        when(aiProvider.analyze(any(AIAnalysisContext.class)))
                .thenThrow(new RuntimeException("Anthropic API unavailable"));

        for (int i = 0; i < 5; i++) {
            aiAnalysisService.analyze(incidentId, tenantId);
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("anthropic-ai");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        AIAnalysisResponse response = aiAnalysisService.analyze(incidentId, tenantId);
        assertThat(response.getSummary()).contains("temporarily unavailable");
    }
}