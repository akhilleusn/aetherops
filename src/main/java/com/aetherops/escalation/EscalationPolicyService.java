package com.aetherops.escalation;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.IncidentSeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EscalationPolicyService {

    /** Applied when a tenant has configured no policy at all — "escalate unresolved P1 after 5 min" out of the box. */
    static final IncidentSeverity DEFAULT_SEVERITY_THRESHOLD = IncidentSeverity.CRITICAL;
    static final int DEFAULT_ESCALATE_AFTER_MINUTES = 5;

    private final EscalationPolicyRepository policyRepository;

    @Transactional
    public EscalationPolicyResponse configure(UUID tenantId, EscalationPolicyRequest request) {
        EscalationPolicy policy = policyRepository.findByTenantIdAndServiceId(tenantId, request.getServiceId())
            .orElseGet(() -> EscalationPolicy.builder().tenantId(tenantId).serviceId(request.getServiceId()).build());

        policy.setSeverityThreshold(request.getSeverityThreshold() != null ? request.getSeverityThreshold() : DEFAULT_SEVERITY_THRESHOLD);
        policy.setEscalateAfterMinutes(request.getEscalateAfterMinutes() != null ? request.getEscalateAfterMinutes() : DEFAULT_ESCALATE_AFTER_MINUTES);
        policy.setEnabled(request.getEnabled() == null || request.getEnabled());

        EscalationPolicy saved = policyRepository.save(policy);
        log.info("Configured escalation policy tenantId={} serviceId={} threshold={} afterMinutes={} enabled={}",
            tenantId, request.getServiceId(), saved.getSeverityThreshold(), saved.getEscalateAfterMinutes(), saved.isEnabled());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<EscalationPolicyResponse> list(UUID tenantId) {
        return policyRepository.findByTenantId(tenantId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public void delete(UUID tenantId, UUID policyId) {
        EscalationPolicy policy = policyRepository.findById(policyId)
            .filter(p -> p.getTenantId().equals(tenantId))
            .orElseThrow(() -> new ResourceNotFoundException("No escalation policy found: " + policyId));
        policyRepository.delete(policy);
    }

    /**
     * The policy that actually governs a given service right now: an exact per-service
     * override if one exists, else the tenant-wide default (service_id IS NULL), else a
     * hardcoded fallback so escalation works with zero configuration.
     */
    @Transactional(readOnly = true)
    public EscalationPolicy resolveEffectivePolicy(UUID tenantId, String serviceId) {
        return policyRepository.findByTenantIdAndServiceId(tenantId, serviceId)
            .or(() -> policyRepository.findByTenantIdAndServiceId(tenantId, null))
            .orElseGet(() -> EscalationPolicy.builder()
                .tenantId(tenantId)
                .serviceId(null)
                .severityThreshold(DEFAULT_SEVERITY_THRESHOLD)
                .escalateAfterMinutes(DEFAULT_ESCALATE_AFTER_MINUTES)
                .enabled(true)
                .build());
    }

    private EscalationPolicyResponse toResponse(EscalationPolicy policy) {
        return EscalationPolicyResponse.builder()
            .id(policy.getId())
            .tenantId(policy.getTenantId())
            .serviceId(policy.getServiceId())
            .severityThreshold(policy.getSeverityThreshold())
            .escalateAfterMinutes(policy.getEscalateAfterMinutes())
            .enabled(policy.isEnabled())
            .createdAt(policy.getCreatedAt())
            .updatedAt(policy.getUpdatedAt())
            .build();
    }
}
