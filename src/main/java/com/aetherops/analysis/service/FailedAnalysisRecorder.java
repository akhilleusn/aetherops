package com.aetherops.analysis.service;

import com.aetherops.analysis.model.AIAnalysis;
import com.aetherops.analysis.model.AnalysisStatus;
import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.audit.model.AuditAction;
import com.aetherops.audit.model.AuditStatus;
import com.aetherops.audit.service.AuditLogService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Records a failed AI analysis attempt (provider call failed, circuit breaker open) so it's
 * visible instead of silently vanishing. Deliberately a separate bean from
 * {@link AIAnalysisService}: resilience4j invokes {@code analyzeFallback} via reflection on the
 * raw bean, bypassing the Spring AOP proxy entirely, so neither {@code @Transactional} on the
 * fallback method itself nor a bare repository call from it would reliably work -- the same
 * self-invocation/tenant-context-propagation gap already fixed elsewhere in this codebase
 * (see IncidentSlackMetadataUpdater). Calling this bean from analyzeFallback is a normal
 * cross-bean call through its own proxy, so its @Transactional actually takes effect.
 */
@Service
@RequiredArgsConstructor
public class FailedAnalysisRecorder {

    private final AIAnalysisRepository aiAnalysisRepository;
    private final AuditLogService auditLogService;
    private final MeterRegistry meterRegistry;

    @Transactional
    public void record(UUID incidentId, UUID tenantId, String reason) {
        int version = (int) aiAnalysisRepository.countByIncidentId(incidentId) + 1;

        aiAnalysisRepository.saveAndFlush(AIAnalysis.builder()
            .incidentId(incidentId)
            .tenantId(tenantId)
            .status(AnalysisStatus.FAILED)
            .errorMessage(reason)
            .confidenceScore(0.0)
            .analysisVersion(version)
            .evidenceCount(0)
            .build());

        auditLogService.record(
            tenantId, AuditAction.ANALYSIS_FAILED, AuditStatus.FAILURE,
            "INCIDENT", incidentId,
            "AI analysis attempt failed: " + reason
        );

        Counter.builder("aetherops.ai.analysis.failures")
            .description("Number of AI analysis requests that failed or were rejected by the circuit breaker")
            .tag("tenantId", tenantId.toString())
            .register(meterRegistry)
            .increment();
    }
}
