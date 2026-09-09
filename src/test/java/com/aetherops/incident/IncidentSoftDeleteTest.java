package com.aetherops.incident;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.event.IncidentEventPublisher;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IdempotencyKeyRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.service.IncidentService;
import com.aetherops.slack.SlackNotifier;
import com.aetherops.tenant.TenantContext;
import com.aetherops.webhook.WebhookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentSoftDeleteTest {

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private SlackNotifier slackNotifier;

    @Mock
    private IncidentEventPublisher eventPublisher;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private WebhookService webhookService;

    @InjectMocks
    private IncidentService incidentService;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void softDelete_setsDeletedAt() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Incident incident = Incident.builder()
                .id(id)
                .tenantId(tenantId)
                .title("Test")
                .status(IncidentStatus.OPEN)
                .severity(IncidentSeverity.HIGH)
                .serviceId("svc")
                .fingerprint("fp")
                .eventCount(1)
                .firstSeenAt(Instant.now())
                .lastSeenAt(Instant.now())
                .build();

        TenantContext.set(tenantId);
        when(incidentRepository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        incidentService.softDelete(id);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
    }

    @Test
    void softDelete_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        TenantContext.set(tenantId);
        when(incidentRepository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> incidentService.softDelete(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * Phase 1 Step D: proves the application-layer tenant check alone is sufficient — no
     * database, no RLS involved at all. The incident genuinely exists (with tenant B's id),
     * but the repository is stubbed to answer exactly what the real
     * findByIdAndTenantId(id, callerTenantId) query would: empty, because the row's
     * tenant_id doesn't match the id argument. If IncidentService ever regressed back to a
     * bare findById(id) call, this stub would no longer match the method actually invoked
     * and this test would fail loudly (an UnnecessaryStubbingException / wrong answer),
     * rather than silently passing on RLS the way an integration test alone could.
     */
    @Test
    void softDelete_otherTenantsIncident_throwsNotFound_notForbidden() {
        UUID incidentId = UUID.randomUUID();
        UUID callerTenantId = UUID.randomUUID();

        TenantContext.set(callerTenantId);
        // Deliberately do NOT stub findByIdAndTenantId(incidentId, someOtherTenant) with the
        // incident present under a different tenant, only the query the service actually
        // issues for the CALLER's tenant — matching what a real WHERE id=? AND tenant_id=?
        // query returns when the row belongs to a different tenant: nothing.
        when(incidentRepository.findByIdAndTenantId(incidentId, callerTenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> incidentService.softDelete(incidentId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(incidentRepository, never()).save(any());
    }
}
