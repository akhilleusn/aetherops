package com.aetherops.escalation;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EscalationCheckJobTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private EscalationPolicyService policyService;
    @Mock private EscalationExecutionRepository executionRepository;
    @Mock private EscalationService escalationService;

    @InjectMocks
    private EscalationCheckJob checkJob;

    private final UUID tenantId = UUID.randomUUID();

    private Tenant tenant() {
        Tenant t = new Tenant();
        t.setId(tenantId);
        return t;
    }

    private Incident incident(Instant firstSeenAt, IncidentSeverity severity) {
        return Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).serviceId("db-svc")
            .title("DB down").status(IncidentStatus.OPEN).severity(severity)
            .firstSeenAt(firstSeenAt).lastSeenAt(firstSeenAt)
            .build();
    }

    private EscalationPolicy defaultPolicy() {
        return EscalationPolicy.builder().tenantId(tenantId)
            .severityThreshold(IncidentSeverity.CRITICAL).escalateAfterMinutes(5).enabled(true).build();
    }

    @Test
    void checkAllTenants_incidentPastThreshold_triggersFirstEscalation() {
        when(tenantRepository.findAll()).thenReturn(List.of(tenant()));
        Incident overdue = incident(Instant.now().minus(6, ChronoUnit.MINUTES), IncidentSeverity.CRITICAL);
        when(incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN)).thenReturn(List.of(overdue));
        when(policyService.resolveEffectivePolicy(tenantId, "db-svc")).thenReturn(defaultPolicy());
        when(executionRepository.findTopByIncidentIdOrderByEscalationLevelDesc(overdue.getId())).thenReturn(Optional.empty());

        checkJob.checkAllTenants();

        verify(escalationService).escalate(eq(overdue), any(), eq(1));
    }

    @Test
    void checkAllTenants_incidentNotYetPastThreshold_doesNotEscalate() {
        when(tenantRepository.findAll()).thenReturn(List.of(tenant()));
        Incident fresh = incident(Instant.now().minus(2, ChronoUnit.MINUTES), IncidentSeverity.CRITICAL);
        when(incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN)).thenReturn(List.of(fresh));
        when(policyService.resolveEffectivePolicy(tenantId, "db-svc")).thenReturn(defaultPolicy());

        checkJob.checkAllTenants();

        verifyNoInteractions(escalationService);
    }

    @Test
    void checkAllTenants_alreadyEscalatedOnce_waitsForNextIntervalBeforeLevelTwo() {
        when(tenantRepository.findAll()).thenReturn(List.of(tenant()));
        Incident overdue = incident(Instant.now().minus(7, ChronoUnit.MINUTES), IncidentSeverity.CRITICAL);
        when(incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN)).thenReturn(List.of(overdue));
        when(policyService.resolveEffectivePolicy(tenantId, "db-svc")).thenReturn(defaultPolicy());
        when(executionRepository.findTopByIncidentIdOrderByEscalationLevelDesc(overdue.getId()))
            .thenReturn(Optional.of(EscalationExecution.builder().escalationLevel(1).build()));

        checkJob.checkAllTenants();

        // level 2 requires 10 minutes elapsed (5 * 2); only 7 have passed
        verifyNoInteractions(escalationService);
    }

    @Test
    void checkAllTenants_alreadyEscalatedOnce_pastSecondInterval_escalatesToLevelTwo() {
        when(tenantRepository.findAll()).thenReturn(List.of(tenant()));
        Incident overdue = incident(Instant.now().minus(11, ChronoUnit.MINUTES), IncidentSeverity.CRITICAL);
        when(incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN)).thenReturn(List.of(overdue));
        when(policyService.resolveEffectivePolicy(tenantId, "db-svc")).thenReturn(defaultPolicy());
        when(executionRepository.findTopByIncidentIdOrderByEscalationLevelDesc(overdue.getId()))
            .thenReturn(Optional.of(EscalationExecution.builder().escalationLevel(1).build()));

        checkJob.checkAllTenants();

        verify(escalationService).escalate(eq(overdue), any(), eq(2));
    }

    @Test
    void checkAllTenants_belowSeverityThreshold_doesNotEscalate() {
        when(tenantRepository.findAll()).thenReturn(List.of(tenant()));
        Incident overdue = incident(Instant.now().minus(10, ChronoUnit.MINUTES), IncidentSeverity.LOW);
        when(incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN)).thenReturn(List.of(overdue));
        when(policyService.resolveEffectivePolicy(tenantId, "db-svc")).thenReturn(defaultPolicy());

        checkJob.checkAllTenants();

        verifyNoInteractions(escalationService);
    }

    @Test
    void checkAllTenants_policyDisabled_doesNotEscalate() {
        when(tenantRepository.findAll()).thenReturn(List.of(tenant()));
        Incident overdue = incident(Instant.now().minus(10, ChronoUnit.MINUTES), IncidentSeverity.CRITICAL);
        when(incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN)).thenReturn(List.of(overdue));
        EscalationPolicy disabled = EscalationPolicy.builder().tenantId(tenantId)
            .severityThreshold(IncidentSeverity.CRITICAL).escalateAfterMinutes(5).enabled(false).build();
        when(policyService.resolveEffectivePolicy(tenantId, "db-svc")).thenReturn(disabled);

        checkJob.checkAllTenants();

        verifyNoInteractions(escalationService);
    }

    @Test
    void checkAllTenants_exceptionForOneTenant_doesNotAbortSweepForOthers() {
        Tenant other = new Tenant();
        UUID otherTenantId = UUID.randomUUID();
        other.setId(otherTenantId);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant(), other));
        when(incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN)).thenThrow(new RuntimeException("boom"));
        when(incidentRepository.findByTenantIdAndStatus(otherTenantId, IncidentStatus.OPEN)).thenReturn(List.of());

        checkJob.checkAllTenants();

        verify(incidentRepository).findByTenantIdAndStatus(otherTenantId, IncidentStatus.OPEN);
    }
}
