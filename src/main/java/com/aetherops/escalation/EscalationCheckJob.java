package com.aetherops.escalation;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sweeps every tenant's still-OPEN incidents for ones due to escalate. Acknowledging an
 * incident (OPEN -> INVESTIGATING) or resolving it removes it from this sweep entirely —
 * escalation only chases incidents nobody has responded to yet. Mirrors
 * {@code com.aetherops.sla.SlaMonitoringJob}'s per-tenant TenantContext sweep pattern.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EscalationCheckJob {

    private final TenantRepository tenantRepository;
    private final IncidentRepository incidentRepository;
    private final EscalationPolicyService policyService;
    private final EscalationExecutionRepository executionRepository;
    private final EscalationService escalationService;

    @Scheduled(fixedDelay = 60_000)
    public void checkAllTenants() {
        List<UUID> tenantIds = tenantRepository.findAll().stream().map(Tenant::getId).toList();
        log.debug("Running escalation sweep for {} tenants", tenantIds.size());

        for (UUID tenantId : tenantIds) {
            try {
                TenantContext.set(tenantId);
                checkTenant(tenantId);
            } catch (Exception e) {
                log.error("Escalation sweep failed for tenantId={}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void checkTenant(UUID tenantId) {
        List<Incident> openIncidents = incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN);
        for (Incident incident : openIncidents) {
            checkIncident(tenantId, incident);
        }
    }

    private void checkIncident(UUID tenantId, Incident incident) {
        EscalationPolicy policy = policyService.resolveEffectivePolicy(tenantId, incident.getServiceId());
        if (!policy.isEnabled()) {
            return;
        }
        if (incident.getSeverity().ordinal() > policy.getSeverityThreshold().ordinal()) {
            return; // less severe than the policy's threshold
        }

        int currentLevel = executionRepository.findTopByIncidentIdOrderByEscalationLevelDesc(incident.getId())
            .map(EscalationExecution::getEscalationLevel)
            .orElse(0);
        int nextLevel = currentLevel + 1;
        long requiredMinutes = (long) policy.getEscalateAfterMinutes() * nextLevel;
        long elapsedMinutes = Duration.between(incident.getFirstSeenAt(), Instant.now()).toMinutes();

        if (elapsedMinutes >= requiredMinutes) {
            escalationService.escalate(incident, policy, nextLevel);
        }
    }
}
