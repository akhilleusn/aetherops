package com.aetherops.escalation;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.oncall.OnCallService;
import com.aetherops.push.PushNotificationService;
import com.aetherops.slack.SlackConversationService;
import com.aetherops.slack.SlackWorkspace;
import com.aetherops.slack.SlackWorkspaceRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EscalationServiceTest {

    @Mock private EscalationExecutionRepository executionRepository;
    @Mock private OnCallService onCallService;
    @Mock private PushNotificationService pushNotificationService;
    @Mock private SlackWorkspaceRepository slackWorkspaceRepository;
    @Mock private SlackConversationService slackConversationService;

    @InjectMocks
    private EscalationService escalationService;

    private final UUID tenantId = UUID.randomUUID();

    private Incident incident(String slackChannelId) {
        return Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).serviceId("db-svc")
            .title("DB down").status(IncidentStatus.OPEN).severity(IncidentSeverity.CRITICAL)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now())
            .slackChannelId(slackChannelId)
            .build();
    }

    private EscalationPolicy policy() {
        return EscalationPolicy.builder().id(UUID.randomUUID()).tenantId(tenantId)
            .severityThreshold(IncidentSeverity.CRITICAL).escalateAfterMinutes(5).enabled(true).build();
    }

    @Test
    void escalate_recordsExecutionWithGivenLevel() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        escalationService.escalate(incident(null), policy(), 2);

        ArgumentCaptor<EscalationExecution> captor = ArgumentCaptor.forClass(EscalationExecution.class);
        verify(executionRepository).save(captor.capture());
        assertThat(captor.getValue().getEscalationLevel()).isEqualTo(2);
    }

    @Test
    void escalate_pagesOnCallDirectly() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        escalationService.escalate(incident(null), policy(), 1);

        verify(onCallService).pageOnCall(eq(tenantId), eq("db-svc"), contains("ESCALATED"));
    }

    @Test
    void escalate_sendsPushAlert() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        escalationService.escalate(incident(null), policy(), 1);

        verify(pushNotificationService).sendPageAlert(eq(tenantId), contains("ESCALATED"), eq("DB down"));
    }

    @Test
    void escalate_noSlackChannelOnIncident_skipsSlackLookupEntirely() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        escalationService.escalate(incident(null), policy(), 1);

        verifyNoInteractions(slackWorkspaceRepository);
    }

    @Test
    void escalate_slackChannelPresentButNoWorkspace_doesNotThrow() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(slackWorkspaceRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        escalationService.escalate(incident("C123"), policy(), 1);

        verifyNoInteractions(slackConversationService);
    }

    @Test
    void escalate_slackChannelAndWorkspacePresent_postsEscalationMessage() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        SlackWorkspace workspace = SlackWorkspace.builder().tenantId(tenantId).build();
        when(slackWorkspaceRepository.findByTenantId(tenantId)).thenReturn(Optional.of(workspace));

        escalationService.escalate(incident("C123"), policy(), 3);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackConversationService).postMessage(eq(workspace), eq("C123"), textCaptor.capture());
        assertThat(textCaptor.getValue()).contains("level 3");
    }
}
