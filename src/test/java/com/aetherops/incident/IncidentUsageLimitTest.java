package com.aetherops.incident;

import com.aetherops.common.exception.UsageLimitExceededException;
import com.aetherops.incident.event.IncidentEventPublisher;
import com.aetherops.incident.model.*;
import com.aetherops.incident.repository.IdempotencyKeyRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.service.IncidentService;
import com.aetherops.slack.SlackNotifier;
import com.aetherops.usage.model.UsageType;
import com.aetherops.usage.service.UsageLimitService;
import com.aetherops.webhook.WebhookService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentUsageLimitTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private SlackNotifier slackNotifier;
    @Mock private IncidentEventPublisher eventPublisher;
    @Mock private IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock private WebhookService webhookService;
    @Mock private UsageLimitService usageLimitService;

    @InjectMocks
    private IncidentService incidentService;

    @Test
    void create_checksIncidentCreationLimit_beforeSavingIncident() {
        UUID tenantId = UUID.randomUUID();
        CreateIncidentRequest request = new CreateIncidentRequest(
            tenantId, null, "Test", null, IncidentSeverity.HIGH, "svc", "prod");

        doThrow(new UsageLimitExceededException(tenantId, UsageType.INCIDENT_CREATION,
                com.aetherops.tenant.model.Plan.FREE, 5))
            .when(usageLimitService).checkAndIncrement(tenantId, UsageType.INCIDENT_CREATION);

        assertThatThrownBy(() -> incidentService.create(request, null))
            .isInstanceOf(UsageLimitExceededException.class);

        verify(incidentRepository, never()).save(any());
        verify(slackNotifier, never()).notify(any());
        verify(webhookService, never()).deliver(any(), any(), any());
    }

    @Test
    void create_withIdempotentRetry_doesNotConsumeQuotaAgain() {
        UUID tenantId = UUID.randomUUID();
        UUID existingIncidentId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        CreateIncidentRequest request = new CreateIncidentRequest(
            tenantId, null, "Test", null, IncidentSeverity.HIGH, "svc", "prod");

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
            .firstSeenAt(java.time.Instant.now()).lastSeenAt(java.time.Instant.now()).build();

        when(incidentRepository.findById(existingIncidentId)).thenReturn(Optional.of(existing));

        incidentService.create(request, key);

        verifyNoInteractions(usageLimitService);
    }
}
