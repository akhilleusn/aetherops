package com.aetherops.incident.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.event.IncidentEventPublisher;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentResponse;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.model.UpdateIncidentRequest;
import com.aetherops.incident.repository.IdempotencyKeyRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.severity.SeverityDetector;
import com.aetherops.oncall.IncidentPagingService;
import com.aetherops.oncall.IncidentTimeline;
import com.aetherops.oncall.IncidentTimelineService;
import com.aetherops.slack.IncidentSlackNotificationService;
import com.aetherops.tenant.TenantContext;
import com.aetherops.usage.service.UsageLimitService;
import com.aetherops.webhook.WebhookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

/**
 * Phase 1 Step D: IncidentService.findById and .update have no application-layer
 * protection beyond IncidentRepository.findByIdAndTenantId — RLS is a backstop, not the
 * primary control anymore. These are pure Mockito unit tests — no Spring context, no
 * database, no RLS involved at all — proving the application-layer tenant check ALONE is
 * sufficient, independent of whatever the database also happens to enforce. A regression
 * back to a bare findById(id) call would make the stubs below go unmatched (Mockito
 * returns null/empty for un-stubbed calls), failing these tests loudly rather than
 * silently passing on RLS alone.
 */
@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private IncidentSlackNotificationService incidentSlackNotificationService;
    @Mock private IncidentEventPublisher eventPublisher;
    @Mock private IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock private WebhookService webhookService;
    @Mock private UsageLimitService usageLimitService;
    @Mock private IncidentTemplateService templateService;
    @Mock private SeverityDetector severityDetector;
    @Mock private IncidentTimelineService timelineService;
    @Mock private IncidentPagingService pagingService;

    @InjectMocks
    private IncidentService incidentService;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private Incident sampleIncident(UUID id, UUID tenantId) {
        Instant now = Instant.now();
        return Incident.builder()
            .id(id).tenantId(tenantId)
            .title("Test incident")
            .status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.LOW)
            .serviceId("svc")
            .fingerprint("fp")
            .eventCount(1)
            .firstSeenAt(now).lastSeenAt(now)
            .build();
    }

    // ── findById ─────────────────────────────────────────────────────────

    @Test
    void findById_ownTenant_returnsIncident() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        when(incidentRepository.findByIdAndTenantId(id, tenantId))
            .thenReturn(Optional.of(sampleIncident(id, tenantId)));

        IncidentResponse response = incidentService.findById(id);

        assertThat(response.getId()).isEqualTo(id);
    }

    @Test
    void findById_otherTenantsIncident_throwsNotFound_notForbidden() {
        UUID id = UUID.randomUUID();
        UUID callerTenantId = UUID.randomUUID();
        TenantContext.set(callerTenantId);
        // The incident genuinely exists — just under a different tenant. The real query
        // (WHERE id = ? AND tenant_id = ?) would return nothing for this combination, so
        // that's exactly what's stubbed: no row present for (id, callerTenantId).
        when(incidentRepository.findByIdAndTenantId(id, callerTenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> incidentService.findById(id))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── update ───────────────────────────────────────────────────────────

    @Test
    void update_ownTenant_appliesChange() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        when(incidentRepository.findByIdAndTenantId(id, tenantId))
            .thenReturn(Optional.of(sampleIncident(id, tenantId)));
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateIncidentRequest request = new UpdateIncidentRequest();
        request.setTitle("Updated title");

        IncidentResponse response = incidentService.update(id, request);

        assertThat(response.getTitle()).isEqualTo("Updated title");
    }

    @Test
    void update_otherTenantsIncident_throwsNotFound_notForbidden() {
        UUID id = UUID.randomUUID();
        UUID callerTenantId = UUID.randomUUID();
        TenantContext.set(callerTenantId);
        when(incidentRepository.findByIdAndTenantId(id, callerTenantId)).thenReturn(Optional.empty());

        UpdateIncidentRequest request = new UpdateIncidentRequest();
        request.setTitle("Attacker's edit");

        assertThatThrownBy(() -> incidentService.update(id, request))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(incidentRepository, never()).save(any());
    }

    // ── timeline entries on status changes ───────────────────────────────

    @Test
    void update_toResolved_recordsResolvedTimelineEntry() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        when(incidentRepository.findByIdAndTenantId(id, tenantId))
            .thenReturn(Optional.of(sampleIncident(id, tenantId)));
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        incidentService.update(id, statusRequest(IncidentStatus.RESOLVED));

        verify(timelineService).record(tenantId, id, IncidentTimeline.TimelineEventType.RESOLVED,
            IncidentSeverity.LOW, "Incident resolved");
    }

    @Test
    void update_toClosed_recordsClosedTimelineEntry() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        Incident resolved = sampleIncident(id, tenantId);
        resolved.setStatus(IncidentStatus.RESOLVED);
        resolved.setResolvedAt(Instant.now());
        when(incidentRepository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(resolved));
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        IncidentResponse response = incidentService.update(id, statusRequest(IncidentStatus.CLOSED));

        assertThat(response.getStatus()).isEqualTo(IncidentStatus.CLOSED);
        verify(timelineService).record(tenantId, id, IncidentTimeline.TimelineEventType.CLOSED,
            IncidentSeverity.LOW, "Incident closed");
    }

    @Test
    void update_toClosed_whenAlreadyClosed_recordsNothing() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        Incident closed = sampleIncident(id, tenantId);
        closed.setStatus(IncidentStatus.CLOSED);
        closed.setResolvedAt(Instant.now());
        when(incidentRepository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(closed));
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        incidentService.update(id, statusRequest(IncidentStatus.CLOSED));

        verifyNoInteractions(timelineService);
    }

    private static UpdateIncidentRequest statusRequest(IncidentStatus status) {
        UpdateIncidentRequest request = new UpdateIncidentRequest();
        request.setStatus(status);
        return request;
    }
}
