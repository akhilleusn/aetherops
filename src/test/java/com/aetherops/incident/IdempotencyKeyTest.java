package com.aetherops.incident;

import com.aetherops.incident.event.IncidentEventPublisher;
import com.aetherops.incident.model.*;
import com.aetherops.incident.repository.IdempotencyKeyRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.service.IncidentService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyKeyTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private IncidentSlackNotificationService incidentSlackNotificationService;
    @Mock private IncidentEventPublisher eventPublisher;
    @Mock private IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock private WebhookService webhookService;
    @Mock private UsageLimitService usageLimitService;
    @Mock private com.aetherops.incident.service.IncidentTemplateService templateService;
    @Mock private com.aetherops.incident.severity.SeverityDetector severityDetector;
    @Mock private com.aetherops.oncall.IncidentTimelineService timelineService;
    @Mock private com.aetherops.oncall.IncidentPagingService pagingService;

    @InjectMocks
    private IncidentService incidentService;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void create_withNewIdempotencyKey_processesAndSavesKey() {
        String key = UUID.randomUUID().toString();
        UUID tenantId = UUID.randomUUID();

        CreateIncidentRequest request = new CreateIncidentRequest(
                tenantId, null, "Test", null, IncidentSeverity.HIGH, "svc", "prod", null, null);

        when(idempotencyKeyRepository
                .findByIdempotencyKeyAndTenantIdAndResourceType(key, tenantId, "INCIDENT"))
                .thenReturn(Optional.empty());

        Incident saved = Incident.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).title("Test")
                .status(IncidentStatus.OPEN).severity(IncidentSeverity.HIGH)
                .serviceId("svc").fingerprint("fp").eventCount(1)
                .firstSeenAt(Instant.now()).lastSeenAt(Instant.now()).build();

        when(incidentRepository.save(any())).thenReturn(saved);
        when(idempotencyKeyRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        doNothing().when(eventPublisher).publish(any());

        IncidentResponse response = incidentService.create(request, key);

        assertThat(response).isNotNull();
        verify(idempotencyKeyRepository).save(argThat(k ->
                k.getIdempotencyKey().equals(key) &&
                        k.getResourceType().equals("INCIDENT")
        ));
    }

    @Test
    void create_withExistingIdempotencyKey_returnsCachedResponse() {
        String key = UUID.randomUUID().toString();
        UUID tenantId = UUID.randomUUID();
        UUID existingIncidentId = UUID.randomUUID();

        CreateIncidentRequest request = new CreateIncidentRequest(
                tenantId, null, "Test", null, IncidentSeverity.HIGH, "svc", "prod", null, null);

        IdempotencyKey existingKey = IdempotencyKey.builder()
                .idempotencyKey(key).tenantId(tenantId)
                .resourceType("INCIDENT").resourceId(existingIncidentId).build();

        when(idempotencyKeyRepository
                .findByIdempotencyKeyAndTenantIdAndResourceType(key, tenantId, "INCIDENT"))
                .thenReturn(Optional.of(existingKey));

        Incident existing = Incident.builder()
                .id(existingIncidentId).tenantId(tenantId).title("Test")
                .status(IncidentStatus.OPEN).severity(IncidentSeverity.HIGH)
                .serviceId("svc").fingerprint("fp").eventCount(1)
                .firstSeenAt(Instant.now()).lastSeenAt(Instant.now()).build();

        TenantContext.set(tenantId);
        when(incidentRepository.findByIdAndTenantId(existingIncidentId, tenantId))
                .thenReturn(Optional.of(existing));

        IncidentResponse response = incidentService.create(request, key);

        assertThat(response.getId()).isEqualTo(existingIncidentId);
        verify(incidentRepository, never()).save(any());
    }
}