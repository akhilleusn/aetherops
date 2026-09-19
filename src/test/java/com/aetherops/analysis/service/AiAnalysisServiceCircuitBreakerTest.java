package com.aetherops.analysis.service;

import com.aetherops.analysis.model.AIAnalysis;
import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.analysis.model.AIAnalysisResponse;
import com.aetherops.analysis.model.AnalysisStatus;
import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.audit.model.AuditAction;
import com.aetherops.audit.model.AuditStatus;
import com.aetherops.audit.service.AuditLogService;
import com.aetherops.evidence.model.EvidenceItemResponse;
import com.aetherops.evidence.model.EvidenceType;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.evidence.service.EvidencePackService;
import com.aetherops.usage.service.UsageLimitService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.aetherops.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

    @Autowired
    private MeterRegistry meterRegistry;

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

    @Test
    void whenAiProviderFails_failureIsRecorded() {
        when(aiProvider.analyze(any(AIAnalysisContext.class)))
                .thenThrow(new RuntimeException("Anthropic API unavailable"));

        aiAnalysisService.analyze(incidentId, tenantId);

        ArgumentCaptor<AIAnalysis> captor = ArgumentCaptor.forClass(AIAnalysis.class);
        verify(aiAnalysisRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(captor.getValue().getErrorMessage()).contains("Anthropic API unavailable");
        assertThat(captor.getValue().getIncidentId()).isEqualTo(incidentId);
        assertThat(captor.getValue().getTenantId()).isEqualTo(tenantId);

        verify(auditLogService).record(
                eq(tenantId), eq(AuditAction.ANALYSIS_FAILED), eq(AuditStatus.FAILURE),
                eq("INCIDENT"), eq(incidentId), any()
        );

        double failures = meterRegistry
                .counter("aetherops.ai.analysis.failures", "tenantId", tenantId.toString())
                .count();
        assertThat(failures).isEqualTo(1.0);
    }

    @Test
    void whenAiProviderFails_getLatestReturnsFailedAnalysisInsteadOfNotFound() {
        when(aiProvider.analyze(any(AIAnalysisContext.class)))
                .thenThrow(new RuntimeException("Anthropic API unavailable"));

        aiAnalysisService.analyze(incidentId, tenantId);

        ArgumentCaptor<AIAnalysis> captor = ArgumentCaptor.forClass(AIAnalysis.class);
        verify(aiAnalysisRepository).saveAndFlush(captor.capture());
        when(aiAnalysisRepository.findTopByIncidentIdOrderByCreatedAtDesc(incidentId))
                .thenReturn(Optional.of(captor.getValue()));

        AIAnalysisResponse latest = aiAnalysisService.getLatest(incidentId, tenantId);

        assertThat(latest.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(latest.getErrorMessage()).contains("Anthropic API unavailable");
    }

    @Test
    void evidenceSummaryLongerThanConfiguredCap_isTruncatedBeforeReachingProvider() {
        String hugeSummary = "x".repeat(5000); // default cap is 4000

        EvidenceItemResponse item = EvidenceItemResponse.builder()
                .id(UUID.randomUUID())
                .type(EvidenceType.LOG_EVENT)
                .summary(hugeSummary)
                .level("ERROR")
                .timestamp(java.time.Instant.now())
                .build();

        IncidentEvidencePackResponse pack = IncidentEvidencePackResponse.builder()
                .incidentId(incidentId)
                .tenantId(tenantId)
                .evidence(List.of(item))
                .totalEvidenceCount(1)
                .build();
        when(evidencePackService.getEvidencePack(any(), any())).thenReturn(pack);

        // Stays on the same failure path every other test in this class uses -- Mockito
        // captures the argument regardless of whether the stub throws, and truncation happens
        // in buildContext() before aiProvider.analyze() is ever called, so this avoids
        // exercising the (unrelated, unmocked) success path's billing/audit side effects.
        ArgumentCaptor<AIAnalysisContext> contextCaptor = ArgumentCaptor.forClass(AIAnalysisContext.class);
        when(aiProvider.analyze(contextCaptor.capture()))
                .thenThrow(new RuntimeException("Anthropic API unavailable"));

        aiAnalysisService.analyze(incidentId, tenantId);

        String sentSummary = contextCaptor.getValue().getEvidenceItems().get(0).getSummary();
        assertThat(sentSummary.length()).isLessThan(hugeSummary.length());
        assertThat(sentSummary).endsWith("…[truncated]");
    }
}