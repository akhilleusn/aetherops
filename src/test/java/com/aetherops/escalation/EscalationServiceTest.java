package com.aetherops.escalation;

import com.aetherops.escalation.EscalationExecution.ChannelStatus;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.integration.twilio.TwilioConfig;
import com.aetherops.oncall.OnCallRotation;
import com.aetherops.oncall.OnCallRotationRepository;
import com.aetherops.oncall.OnCallService;
import com.aetherops.oncall.OnCallService.PageOutcome;
import com.aetherops.oncall.OnCallService.PageResult;
import com.aetherops.push.PushNotificationService;
import com.aetherops.slack.SlackConversationService;
import com.aetherops.slack.SlackConversationService.PostedMessage;
import com.aetherops.slack.SlackWorkspace;
import com.aetherops.slack.SlackWorkspaceRepository;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private static final PageResult ACCEPTED = new PageResult(PageOutcome.ACCEPTED, "SM123", "queued");

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
        when(onCallService.pageOnCall(any(), any(), any())).thenReturn(ACCEPTED);

        escalationService.escalate(incident(null), policy(), 2);

        ArgumentCaptor<EscalationExecution> captor = ArgumentCaptor.forClass(EscalationExecution.class);
        verify(executionRepository).save(captor.capture());
        assertThat(captor.getValue().getEscalationLevel()).isEqualTo(2);
    }

    @Test
    void escalate_pagesOnCallDirectly() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(onCallService.pageOnCall(any(), any(), any())).thenReturn(ACCEPTED);

        escalationService.escalate(incident(null), policy(), 1);

        verify(onCallService).pageOnCall(eq(tenantId), eq("db-svc"), contains("ESCALATED"));
    }

    @Test
    void escalate_smsAccepted_doesNotAttemptPushOrSlack() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(onCallService.pageOnCall(any(), any(), any())).thenReturn(ACCEPTED);

        escalationService.escalate(incident("C123"), policy(), 1);

        verifyNoInteractions(pushNotificationService, slackWorkspaceRepository, slackConversationService);
    }

    @Test
    void escalate_smsFails_sendsPushAlert() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(onCallService.pageOnCall(any(), any(), any()))
            .thenReturn(new PageResult(PageOutcome.PROVIDER_ERROR, null, "boom"));
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(true);

        escalationService.escalate(incident(null), policy(), 1);

        verify(pushNotificationService).sendPageAlert(eq(tenantId), contains("ESCALATED"), eq("DB down"));
    }

    // Step H2 (see STEP_REPORT.md): the paging path specifically must not have a slow SMS
    // attempt block the push/Slack fallback -- in production, onCallService.pageOnCall is
    // bounded by Twilio's own (tightened, see TwilioConfig) connect/read timeout; this simulates
    // a call that takes a while right up against that bound and then fails, and proves push
    // still gets attempted rather than the fallback chain stalling or being skipped.
    @Test
    void escalate_slowFailingSmsAttempt_stillFallsBackToPush_doesNotBlockOnIt() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(onCallService.pageOnCall(any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(150);
            return new PageResult(PageOutcome.PROVIDER_ERROR, null, "Twilio timed out");
        });
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(true);

        long start = System.currentTimeMillis();
        escalationService.escalate(incident(null), policy(), 1);
        long elapsedMs = System.currentTimeMillis() - start;

        verify(pushNotificationService).sendPageAlert(eq(tenantId), contains("ESCALATED"), eq("DB down"));
        verifyNoInteractions(slackWorkspaceRepository, slackConversationService);
        assertThat(elapsedMs).isLessThan(2000L);
    }

    @Test
    void escalate_smsAndPushFail_fallsBackToSlack() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(onCallService.pageOnCall(any(), any(), any()))
            .thenReturn(new PageResult(PageOutcome.PROVIDER_ERROR, null, "boom"));
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(false);
        SlackWorkspace workspace = SlackWorkspace.builder().tenantId(tenantId).build();
        when(slackWorkspaceRepository.findByTenantId(tenantId)).thenReturn(Optional.of(workspace));
        when(slackConversationService.postMessage(eq(workspace), eq("C123"), any()))
            .thenReturn(Optional.of(new PostedMessage("C123", "1234.5678")));

        escalationService.escalate(incident("C123"), policy(), 3);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackConversationService).postMessage(eq(workspace), eq("C123"), textCaptor.capture());
        assertThat(textCaptor.getValue()).contains("level 3");
    }

    @Test
    void escalate_smsAndPushFailAndNoSlackChannel_skipsSlackLookupEntirely() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(onCallService.pageOnCall(any(), any(), any()))
            .thenReturn(new PageResult(PageOutcome.PROVIDER_ERROR, null, "boom"));
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(false);

        escalationService.escalate(incident(null), policy(), 1);

        verifyNoInteractions(slackWorkspaceRepository);
    }

    @Test
    void escalate_smsAndPushFailSlackChannelPresentButNoWorkspace_doesNotThrow() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(onCallService.pageOnCall(any(), any(), any()))
            .thenReturn(new PageResult(PageOutcome.PROVIDER_ERROR, null, "boom"));
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(false);
        when(slackWorkspaceRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        escalationService.escalate(incident("C123"), policy(), 1);

        verifyNoInteractions(slackConversationService);
    }

    // --- Step G1 (see STEP_REPORT.md): per-channel delivery status, sequential fallback. ---

    @Test
    void escalate_smsAccepted_recordsSmsAcceptedAndOtherChannelsNotAttempted() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(onCallService.pageOnCall(any(), any(), any())).thenReturn(ACCEPTED);

        escalationService.escalate(incident("C123"), policy(), 1);

        ArgumentCaptor<EscalationExecution> captor = ArgumentCaptor.forClass(EscalationExecution.class);
        verify(executionRepository).save(captor.capture());
        assertThat(captor.getValue().getSmsStatus()).isEqualTo(ChannelStatus.ACCEPTED);
        assertThat(captor.getValue().getPushStatus()).isEqualTo(ChannelStatus.NOT_ATTEMPTED);
        assertThat(captor.getValue().getSlackStatus()).isEqualTo(ChannelStatus.NOT_ATTEMPTED);
    }

    @Test
    void escalate_smsFailsPushAccepted_recordsBothAndSkipsSlack() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(onCallService.pageOnCall(any(), any(), any()))
            .thenReturn(new PageResult(PageOutcome.NOT_CONFIGURED, null, "not configured"));
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(true);

        escalationService.escalate(incident("C123"), policy(), 1);

        ArgumentCaptor<EscalationExecution> captor = ArgumentCaptor.forClass(EscalationExecution.class);
        verify(executionRepository).save(captor.capture());
        assertThat(captor.getValue().getSmsStatus()).isEqualTo(ChannelStatus.FAILED);
        assertThat(captor.getValue().getPushStatus()).isEqualTo(ChannelStatus.ACCEPTED);
        assertThat(captor.getValue().getSlackStatus()).isEqualTo(ChannelStatus.NOT_ATTEMPTED);
        verifyNoInteractions(slackWorkspaceRepository);
    }

    @Test
    void escalate_allThreeChannelsFail_recordsAllFailed() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(onCallService.pageOnCall(any(), any(), any()))
            .thenReturn(new PageResult(PageOutcome.PROVIDER_ERROR, null, "boom"));
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(false);
        SlackWorkspace workspace = SlackWorkspace.builder().tenantId(tenantId).build();
        when(slackWorkspaceRepository.findByTenantId(tenantId)).thenReturn(Optional.of(workspace));
        when(slackConversationService.postMessage(eq(workspace), eq("C123"), any())).thenReturn(Optional.empty());

        escalationService.escalate(incident("C123"), policy(), 1);

        ArgumentCaptor<EscalationExecution> captor = ArgumentCaptor.forClass(EscalationExecution.class);
        verify(executionRepository).save(captor.capture());
        assertThat(captor.getValue().getSmsStatus()).isEqualTo(ChannelStatus.FAILED);
        assertThat(captor.getValue().getPushStatus()).isEqualTo(ChannelStatus.FAILED);
        assertThat(captor.getValue().getSlackStatus()).isEqualTo(ChannelStatus.FAILED);
    }

    @Test
    void escalate_noSlackChannelConfigured_recordsSlackNotAttemptedNotFailed() {
        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(onCallService.pageOnCall(any(), any(), any()))
            .thenReturn(new PageResult(PageOutcome.PROVIDER_ERROR, null, "boom"));
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(false);

        escalationService.escalate(incident(null), policy(), 1);

        ArgumentCaptor<EscalationExecution> captor = ArgumentCaptor.forClass(EscalationExecution.class);
        verify(executionRepository).save(captor.capture());
        assertThat(captor.getValue().getSlackStatus()).isEqualTo(ChannelStatus.NOT_ATTEMPTED);
    }

    /**
     * The test that is the whole point of this batch: force the REAL Twilio SDK call to throw
     * through a real {@code OnCallService} (not a mocked one returning a canned failure), and
     * prove the saved {@code EscalationExecution} shows {@code smsStatus == FAILED}. Before
     * Step G1, this exact scenario resulted in an AuthenticationException caught and logged
     * three levels down, with no record anywhere that the page never went out.
     */
    @Test
    void escalate_realOnCallServiceWithTwilioThrowing_recordsSmsFailed() {
        OnCallRotationRepository rotationRepository = mock(OnCallRotationRepository.class);
        TwilioConfig twilioConfig = mock(TwilioConfig.class);
        when(twilioConfig.isConfigured()).thenReturn(true);
        when(twilioConfig.getFromNumber()).thenReturn("+15550001111");
        OnCallRotation rotation = OnCallRotation.builder()
            .id(1L).tenantId(tenantId).serviceId("db-svc")
            .rotationStartAt(Instant.now()).shiftLengthHours(24)
            .participants(List.of("+15550002222"))
            .build();
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "db-svc")).thenReturn(rotation);

        OnCallService realOnCallService = new OnCallService();
        ReflectionTestUtils.setField(realOnCallService, "rotationRepository", rotationRepository);
        ReflectionTestUtils.setField(realOnCallService, "twilioConfig", twilioConfig);
        ReflectionTestUtils.setField(realOnCallService, "ptoAwarenessService", null);

        EscalationService escalationServiceWithRealOnCall = new EscalationService(
            executionRepository, realOnCallService, pushNotificationService,
            slackWorkspaceRepository, slackConversationService);

        when(executionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(false);

        try (MockedStatic<Message> mockedMessage = mockStatic(Message.class)) {
            MessageCreator creatorMock = mock(MessageCreator.class);
            mockedMessage.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), anyString()))
                .thenReturn(creatorMock);
            when(creatorMock.create()).thenThrow(new ApiException("Twilio rejected this request"));

            escalationServiceWithRealOnCall.escalate(incident(null), policy(), 1);
        }

        ArgumentCaptor<EscalationExecution> captor = ArgumentCaptor.forClass(EscalationExecution.class);
        verify(executionRepository).save(captor.capture());
        assertThat(captor.getValue().getSmsStatus()).isEqualTo(ChannelStatus.FAILED);
        assertThat(captor.getValue().getSmsDetail()).contains("PROVIDER_ERROR");
    }
}
