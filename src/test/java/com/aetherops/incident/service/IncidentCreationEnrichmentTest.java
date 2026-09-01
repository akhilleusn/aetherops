package com.aetherops.incident.service;

import com.aetherops.common.exception.IncidentValidationException;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.event.IncidentEventPublisher;
import com.aetherops.incident.model.*;
import com.aetherops.incident.repository.IdempotencyKeyRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.severity.SeverityDetectionResult;
import com.aetherops.incident.severity.SeverityDetector;
import com.aetherops.oncall.IncidentPagingService;
import com.aetherops.oncall.IncidentTimelineService;
import com.aetherops.slack.IncidentSlackNotificationService;
import com.aetherops.usage.service.UsageLimitService;
import com.aetherops.webhook.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Covers template application, AI severity auto-detection, and tag persistence on incident creation. */
@ExtendWith(MockitoExtension.class)
class IncidentCreationEnrichmentTest {

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

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(idempotencyKeyRepository
            .findByIdempotencyKeyAndTenantIdAndResourceType(any(), any(), any()))
            .thenReturn(Optional.empty());
        lenient().when(incidentRepository.save(any())).thenAnswer(i -> {
            Incident incident = i.getArgument(0);
            if (incident.getId() == null) {
                incident.setId(UUID.randomUUID());
            }
            return incident;
        });
    }

    private CreateIncidentRequest request(String title, String description, IncidentSeverity severity,
                                           String serviceId, Map<String, String> tags, UUID templateId) {
        return new CreateIncidentRequest(tenantId, null, title, description, severity, serviceId, "prod", tags, templateId);
    }

    // ── templates ─────────────────────────────────────────────────────────

    @Test
    void create_withTemplate_fillsInMissingFields() {
        UUID templateId = UUID.randomUUID();
        IncidentTemplate template = IncidentTemplate.builder()
            .id(templateId).tenantId(null)
            .defaultTitle("Database unavailable")
            .defaultDescription("The database is down.")
            .defaultSeverity(IncidentSeverity.CRITICAL)
            .defaultServiceId("db-svc")
            .defaultTags(Map.of("category", "database"))
            .build();
        when(templateService.findVisible(templateId, tenantId)).thenReturn(template);

        CreateIncidentRequest req = request(null, null, null, null, null, templateId);
        incidentService.create(req, null);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        Incident saved = captor.getValue();

        assertThat(saved.getTitle()).isEqualTo("Database unavailable");
        assertThat(saved.getDescription()).isEqualTo("The database is down.");
        assertThat(saved.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(saved.getServiceId()).isEqualTo("db-svc");
        assertThat(saved.getTags()).containsEntry("category", "database");
        verifyNoInteractions(severityDetector);
    }

    @Test
    void create_explicitFieldsOverrideTemplateDefaults() {
        UUID templateId = UUID.randomUUID();
        IncidentTemplate template = IncidentTemplate.builder()
            .id(templateId).tenantId(null)
            .defaultTitle("Database unavailable")
            .defaultSeverity(IncidentSeverity.CRITICAL)
            .defaultServiceId("db-svc")
            .build();
        when(templateService.findVisible(templateId, tenantId)).thenReturn(template);

        CreateIncidentRequest req = request("Custom title", null, IncidentSeverity.LOW, "custom-svc", null, templateId);
        incidentService.create(req, null);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        Incident saved = captor.getValue();

        assertThat(saved.getTitle()).isEqualTo("Custom title");
        assertThat(saved.getSeverity()).isEqualTo(IncidentSeverity.LOW);
        assertThat(saved.getServiceId()).isEqualTo("custom-svc");
    }

    @Test
    void create_unknownTemplateId_throwsResourceNotFound() {
        UUID templateId = UUID.randomUUID();
        when(templateService.findVisible(templateId, tenantId))
            .thenThrow(new ResourceNotFoundException("Incident template not found: " + templateId));

        CreateIncidentRequest req = request("Title", null, IncidentSeverity.HIGH, "svc", null, templateId);

        assertThatThrownBy(() -> incidentService.create(req, null))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void create_noTitleAndNoTemplate_throwsIncidentValidation() {
        CreateIncidentRequest req = request(null, null, IncidentSeverity.HIGH, "svc", null, null);

        assertThatThrownBy(() -> incidentService.create(req, null))
            .isInstanceOf(IncidentValidationException.class)
            .hasMessageContaining("title");
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void create_noServiceIdAndNoTemplate_throwsIncidentValidation() {
        CreateIncidentRequest req = request("Title", null, IncidentSeverity.HIGH, null, null, null);

        assertThatThrownBy(() -> incidentService.create(req, null))
            .isInstanceOf(IncidentValidationException.class)
            .hasMessageContaining("serviceId");
    }

    @Test
    void create_noTemplateId_neverCallsTemplateService() {
        CreateIncidentRequest req = request("Title", null, IncidentSeverity.HIGH, "svc", null, null);
        incidentService.create(req, null);
        verifyNoInteractions(templateService);
    }

    // ── AI severity auto-detection ───────────────────────────────────────

    @Test
    void create_severityOmitted_callsDetectorAndUsesPrediction() {
        when(severityDetector.detect("Title", "desc")).thenReturn(SeverityDetectionResult.builder()
            .predictedSeverity(IncidentSeverity.CRITICAL)
            .confidenceScore(0.8)
            .reasoning("matched 'down'")
            .build());

        CreateIncidentRequest req = request("Title", "desc", null, "svc", null, null);
        incidentService.create(req, null);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    void create_severityProvided_neverCallsDetector() {
        CreateIncidentRequest req = request("Title", "desc", IncidentSeverity.LOW, "svc", null, null);
        incidentService.create(req, null);
        verifyNoInteractions(severityDetector);
    }

    @Test
    void create_severityFromTemplateOnly_stillSkipsDetector() {
        UUID templateId = UUID.randomUUID();
        IncidentTemplate template = IncidentTemplate.builder()
            .id(templateId).tenantId(null).defaultSeverity(IncidentSeverity.MEDIUM).build();
        when(templateService.findVisible(templateId, tenantId)).thenReturn(template);

        CreateIncidentRequest req = request("Title", null, null, "svc", null, templateId);
        incidentService.create(req, null);

        verifyNoInteractions(severityDetector);
        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(IncidentSeverity.MEDIUM);
    }

    // ── tags ──────────────────────────────────────────────────────────────

    @Test
    void create_withTags_persistsThem() {
        CreateIncidentRequest req = request("Title", null, IncidentSeverity.HIGH, "svc",
            Map.of("team", "payments"), null);
        incidentService.create(req, null);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getTags()).containsEntry("team", "payments");
    }

    @Test
    void update_withTags_replacesEntireMap() {
        UUID id = UUID.randomUUID();
        Incident existing = Incident.builder()
            .id(id).tenantId(tenantId).title("T").status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.HIGH).serviceId("svc").fingerprint("fp").eventCount(1)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now())
            .tags(Map.of("old", "value"))
            .build();
        when(incidentRepository.findById(id)).thenReturn(Optional.of(existing));

        UpdateIncidentRequest update = new UpdateIncidentRequest(null, null, null, null, Map.of("new", "value"));
        incidentService.update(id, update);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getTags()).containsExactly(Map.entry("new", "value"));
    }

    @Test
    void update_withoutTags_leavesExistingTagsUntouched() {
        UUID id = UUID.randomUUID();
        Incident existing = Incident.builder()
            .id(id).tenantId(tenantId).title("T").status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.HIGH).serviceId("svc").fingerprint("fp").eventCount(1)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now())
            .tags(Map.of("keep", "me"))
            .build();
        when(incidentRepository.findById(id)).thenReturn(Optional.of(existing));

        UpdateIncidentRequest update = new UpdateIncidentRequest("New title", null, null, null, null);
        incidentService.update(id, update);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getTags()).containsExactly(Map.entry("keep", "me"));
    }

    // ── SLA acknowledgement timestamp ───────────────────────────────────

    @Test
    void update_statusToInvestigating_setsAcknowledgedAt() {
        UUID id = UUID.randomUUID();
        Incident existing = Incident.builder()
            .id(id).tenantId(tenantId).title("T").status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.HIGH).serviceId("svc").fingerprint("fp").eventCount(1)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now())
            .build();
        when(incidentRepository.findById(id)).thenReturn(Optional.of(existing));

        UpdateIncidentRequest update = new UpdateIncidentRequest(null, null, IncidentStatus.INVESTIGATING, null, null);
        incidentService.update(id, update);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getAcknowledgedAt()).isNotNull();
    }

    @Test
    void update_alreadyAcknowledged_doesNotOverwriteAcknowledgedAt() {
        UUID id = UUID.randomUUID();
        Instant firstAck = Instant.now().minusSeconds(120);
        Incident existing = Incident.builder()
            .id(id).tenantId(tenantId).title("T").status(IncidentStatus.INVESTIGATING)
            .severity(IncidentSeverity.HIGH).serviceId("svc").fingerprint("fp").eventCount(1)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now())
            .acknowledgedAt(firstAck)
            .build();
        when(incidentRepository.findById(id)).thenReturn(Optional.of(existing));

        UpdateIncidentRequest update = new UpdateIncidentRequest(null, null, IncidentStatus.INVESTIGATING, null, null);
        incidentService.update(id, update);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getAcknowledgedAt()).isEqualTo(firstAck);
    }

    // ── timeline + paging wiring ─────────────────────────────────────────

    @Test
    void create_alwaysRecordsCreatedTimelineEvent() {
        CreateIncidentRequest req = request("Title", null, IncidentSeverity.LOW, "svc", null, null);
        incidentService.create(req, null);

        verify(timelineService).record(eq(tenantId), any(), eq(com.aetherops.oncall.IncidentTimeline.TimelineEventType.CREATED),
            eq(IncidentSeverity.LOW), any());
    }

    @Test
    void create_alertableSeverity_triggersPaging() {
        CreateIncidentRequest req = request("Title", null, IncidentSeverity.CRITICAL, "svc", null, null);
        incidentService.create(req, null);

        verify(pagingService).maybePage(eq(tenantId), any(), eq("svc"), eq("Title"), eq(IncidentSeverity.CRITICAL));
    }

    @Test
    void create_nonAlertableSeverity_doesNotTriggerPaging() {
        CreateIncidentRequest req = request("Title", null, IncidentSeverity.LOW, "svc", null, null);
        incidentService.create(req, null);

        verifyNoInteractions(pagingService);
    }

    @Test
    void update_severityChanged_recordsSeverityChangedTimelineEvent() {
        UUID id = UUID.randomUUID();
        Incident existing = Incident.builder()
            .id(id).tenantId(tenantId).title("T").status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.LOW).serviceId("svc").fingerprint("fp").eventCount(1)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now())
            .build();
        when(incidentRepository.findById(id)).thenReturn(Optional.of(existing));

        UpdateIncidentRequest update = new UpdateIncidentRequest(null, null, null, IncidentSeverity.CRITICAL, null);
        incidentService.update(id, update);

        verify(timelineService).record(eq(tenantId), eq(id),
            eq(com.aetherops.oncall.IncidentTimeline.TimelineEventType.SEVERITY_CHANGED), eq(IncidentSeverity.CRITICAL), any());
    }

    @Test
    void update_toResolved_recordsResolvedTimelineEvent() {
        UUID id = UUID.randomUUID();
        Incident existing = Incident.builder()
            .id(id).tenantId(tenantId).title("T").status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.HIGH).serviceId("svc").fingerprint("fp").eventCount(1)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now())
            .build();
        when(incidentRepository.findById(id)).thenReturn(Optional.of(existing));

        UpdateIncidentRequest update = new UpdateIncidentRequest(null, null, IncidentStatus.RESOLVED, null, null);
        incidentService.update(id, update);

        verify(timelineService).record(eq(tenantId), eq(id),
            eq(com.aetherops.oncall.IncidentTimeline.TimelineEventType.RESOLVED), eq(IncidentSeverity.HIGH), any());
    }

    @Test
    void update_escalatingIntoAlertable_triggersPaging() {
        UUID id = UUID.randomUUID();
        Incident existing = Incident.builder()
            .id(id).tenantId(tenantId).title("T").status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.LOW).serviceId("svc").fingerprint("fp").eventCount(1)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now())
            .build();
        when(incidentRepository.findById(id)).thenReturn(Optional.of(existing));

        UpdateIncidentRequest update = new UpdateIncidentRequest(null, null, null, IncidentSeverity.CRITICAL, null);
        incidentService.update(id, update);

        verify(pagingService).maybePage(eq(tenantId), eq(id), eq("svc"), eq("T"), eq(IncidentSeverity.CRITICAL));
    }
}
