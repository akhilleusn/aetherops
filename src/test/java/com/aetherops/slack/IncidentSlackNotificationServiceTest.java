package com.aetherops.slack;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentSlackNotificationServiceTest {

    @Mock private SlackWorkspaceRepository workspaceRepository;
    @Mock private SlackConversationService conversationService;
    @Mock private SlackNotifier legacyNotifier;
    @Mock private IncidentRepository incidentRepository;

    @InjectMocks
    private IncidentSlackNotificationService notificationService;

    private final UUID tenantId = UUID.randomUUID();

    private Incident incident() {
        return Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId)
            .title("DB down").severity(IncidentSeverity.CRITICAL).serviceId("db-svc")
            .build();
    }

    @Test
    void notify_noConnectedWorkspace_usesLegacyWebhook() {
        when(workspaceRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        Incident incident = incident();

        notificationService.notify(incident);

        verify(legacyNotifier).notify(incident);
        verifyNoInteractions(conversationService);
    }

    @Test
    void notify_connectedWorkspace_botPostSucceeds_persistsChannelAndTs() {
        SlackWorkspace workspace = SlackWorkspace.builder().tenantId(tenantId).defaultChannelId("C1").build();
        when(workspaceRepository.findByTenantId(tenantId)).thenReturn(Optional.of(workspace));

        Incident incident = incident();
        when(conversationService.postIncidentAlert(workspace, incident))
            .thenReturn(Optional.of(new SlackConversationService.PostedMessage("C1", "1111.2222")));

        notificationService.notify(incident);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getSlackChannelId()).isEqualTo("C1");
        assertThat(captor.getValue().getSlackThreadTs()).isEqualTo("1111.2222");
        verifyNoInteractions(legacyNotifier);
    }

    @Test
    void notify_connectedWorkspace_botPostFails_fallsBackToLegacyWebhook() {
        SlackWorkspace workspace = SlackWorkspace.builder().tenantId(tenantId).defaultChannelId("C1").build();
        when(workspaceRepository.findByTenantId(tenantId)).thenReturn(Optional.of(workspace));

        Incident incident = incident();
        when(conversationService.postIncidentAlert(workspace, incident)).thenReturn(Optional.empty());

        notificationService.notify(incident);

        verify(legacyNotifier).notify(incident);
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void notify_connectedWorkspace_botPostFails_doesNotPersistChannelOrTs() {
        SlackWorkspace workspace = SlackWorkspace.builder().tenantId(tenantId).defaultChannelId("C1").build();
        when(workspaceRepository.findByTenantId(tenantId)).thenReturn(Optional.of(workspace));
        when(conversationService.postIncidentAlert(eq(workspace), any())).thenReturn(Optional.empty());

        notificationService.notify(incident());

        verifyNoInteractions(incidentRepository);
    }
}
