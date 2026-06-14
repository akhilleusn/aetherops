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
import com.aetherops.webhook.WebhookService;
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

    @Test
    void softDelete_setsDeletedAt() {
        UUID id = UUID.randomUUID();
        Incident incident = Incident.builder()
                .id(id)
                .tenantId(UUID.randomUUID())
                .title("Test")
                .status(IncidentStatus.OPEN)
                .severity(IncidentSeverity.HIGH)
                .serviceId("svc")
                .fingerprint("fp")
                .eventCount(1)
                .firstSeenAt(Instant.now())
                .lastSeenAt(Instant.now())
                .build();

        when(incidentRepository.findById(id)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        incidentService.softDelete(id);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
    }

    @Test
    void softDelete_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(incidentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> incidentService.softDelete(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}