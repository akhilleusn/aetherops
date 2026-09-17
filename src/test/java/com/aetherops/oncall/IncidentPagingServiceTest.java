package com.aetherops.oncall;

import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.integration.twilio.TwilioConfig;
import com.aetherops.oncall.OnCallService.PageOutcome;
import com.aetherops.oncall.OnCallService.PageResult;
import com.aetherops.push.PushNotificationService;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentPagingServiceTest {

    @Mock private OnCallService onCallService;
    @Mock private AlertDeduplication alertDeduplication;
    @Mock private IncidentTimelineService timelineService;
    @Mock private PushNotificationService pushNotificationService;

    @InjectMocks
    private IncidentPagingService pagingService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    private static final PageResult ACCEPTED = new PageResult(PageOutcome.ACCEPTED, "SM123", "queued");

    private DeduplicatedAlert unlinkedDedup() {
        return DeduplicatedAlert.builder().tenantId(tenantId).alertCount(1).incidentId(null).build();
    }

    @BeforeEach
    void defaultToSuccessfulPage() {
        lenient().when(onCallService.pageOnCall(any(), any(), any())).thenReturn(ACCEPTED);
        lenient().when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(true);
    }

    @Test
    void maybePage_criticalSeverity_pagesOnCall() {
        when(alertDeduplication.record(any())).thenReturn(unlinkedDedup());

        pagingService.maybePage(tenantId, incidentId, "db-svc", "DB down", IncidentSeverity.CRITICAL);

        verify(onCallService).pageOnCall(tenantId, "db-svc", "DB down");
    }

    @Test
    void maybePage_alertable_alsoSendsPushNotification() {
        when(alertDeduplication.record(any())).thenReturn(unlinkedDedup());

        pagingService.maybePage(tenantId, incidentId, "db-svc", "DB down", IncidentSeverity.CRITICAL);

        verify(pushNotificationService).sendPageAlert(eq(tenantId), any(), eq("DB down"));
    }

    @Test
    void maybePage_nonAlertable_doesNotSendPushNotification() {
        pagingService.maybePage(tenantId, incidentId, "db-svc", "minor", IncidentSeverity.LOW);

        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void maybePage_highSeverity_pagesOnCall() {
        when(alertDeduplication.record(any())).thenReturn(unlinkedDedup());

        pagingService.maybePage(tenantId, incidentId, "db-svc", "DB slow", IncidentSeverity.HIGH);

        verify(onCallService).pageOnCall(any(), any(), any());
    }

    @Test
    void maybePage_mediumSeverity_doesNotPage() {
        pagingService.maybePage(tenantId, incidentId, "db-svc", "DB blip", IncidentSeverity.MEDIUM);

        verifyNoInteractions(onCallService);
        verifyNoInteractions(alertDeduplication);
    }

    @Test
    void maybePage_lowSeverity_doesNotPage() {
        pagingService.maybePage(tenantId, incidentId, "db-svc", "minor", IncidentSeverity.LOW);

        verifyNoInteractions(onCallService);
    }

    @Test
    void maybePage_alertAlreadyLinkedToAnIncident_skipsRepeatPage() {
        DeduplicatedAlert alreadyLinked = DeduplicatedAlert.builder()
            .tenantId(tenantId).alertCount(3).incidentId(UUID.randomUUID()).build();
        when(alertDeduplication.record(any())).thenReturn(alreadyLinked);

        pagingService.maybePage(tenantId, incidentId, "db-svc", "DB down", IncidentSeverity.CRITICAL);

        verifyNoInteractions(onCallService);
        verify(alertDeduplication, never()).linkIncident(any(), any());
    }

    @Test
    void maybePage_firstSighting_linksIncidentToDedupRecord() {
        DeduplicatedAlert dedup = unlinkedDedup();
        when(alertDeduplication.record(any())).thenReturn(dedup);

        pagingService.maybePage(tenantId, incidentId, "db-svc", "DB down", IncidentSeverity.CRITICAL);

        verify(alertDeduplication).linkIncident(dedup, incidentId);
    }

    @Test
    void maybePage_successfulPage_recordsEngineerPagedTimelineEvent() {
        when(alertDeduplication.record(any())).thenReturn(unlinkedDedup());

        pagingService.maybePage(tenantId, incidentId, "db-svc", "DB down", IncidentSeverity.CRITICAL);

        verify(timelineService).record(eq(tenantId), eq(incidentId),
            eq(IncidentTimeline.TimelineEventType.ENGINEER_PAGED), eq(SeverityLevel.P1), any());
    }

    @Test
    void maybePage_dedupRecordUsesMappedSeverityLevel() {
        when(alertDeduplication.record(any())).thenReturn(unlinkedDedup());

        pagingService.maybePage(tenantId, incidentId, "db-svc", "DB down", IncidentSeverity.HIGH);

        ArgumentCaptor<AlertDeduplication.Alert> captor = ArgumentCaptor.forClass(AlertDeduplication.Alert.class);
        verify(alertDeduplication).record(captor.capture());
        assertThat(captor.getValue().severity()).isEqualTo(SeverityLevel.P2);
        assertThat(captor.getValue().service()).isEqualTo("db-svc");
    }

    // --- Step G1 (see STEP_REPORT.md): a page that doesn't send must never be recorded as
    // ENGINEER_PAGED. ---

    @Test
    void maybePage_smsAndPushBothFail_recordsPageFailedNotEngineerPaged() {
        when(alertDeduplication.record(any())).thenReturn(unlinkedDedup());
        when(onCallService.pageOnCall(any(), any(), any()))
            .thenReturn(new PageResult(PageOutcome.PROVIDER_ERROR, null, "Twilio rejected the request"));
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(false);

        pagingService.maybePage(tenantId, incidentId, "db-svc", "DB down", IncidentSeverity.CRITICAL);

        verify(timelineService).record(eq(tenantId), eq(incidentId),
            eq(IncidentTimeline.TimelineEventType.PAGE_FAILED), eq(SeverityLevel.P1), any());
        verify(timelineService, never()).record(any(), any(), eq(IncidentTimeline.TimelineEventType.ENGINEER_PAGED), any(SeverityLevel.class), any());
    }

    // Step H2 (see STEP_REPORT.md): proves a slow SMS attempt does not block the push fallback
    // that follows it. onCallService.pageOnCall is bounded in production by Twilio's own
    // (tightened, see TwilioConfig) connect/read timeout -- "slow" here means "took a while and
    // then failed," simulating what a real call looks like right up against that bound, without
    // this test itself depending on a real network call or the full connect+read duration.
    @Test
    void maybePage_slowFailingSmsAttempt_stillAttemptsPushFallback_doesNotBlockOnIt() {
        when(alertDeduplication.record(any())).thenReturn(unlinkedDedup());
        when(onCallService.pageOnCall(any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(150);
            return new PageResult(PageOutcome.PROVIDER_ERROR, null, "Twilio timed out");
        });
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(true);

        long start = System.currentTimeMillis();
        pagingService.maybePage(tenantId, incidentId, "db-svc", "DB down", IncidentSeverity.CRITICAL);
        long elapsedMs = System.currentTimeMillis() - start;

        verify(onCallService).pageOnCall(tenantId, "db-svc", "DB down");
        verify(pushNotificationService).sendPageAlert(eq(tenantId), any(), eq("DB down"));
        verify(timelineService).record(eq(tenantId), eq(incidentId),
            eq(IncidentTimeline.TimelineEventType.ENGINEER_PAGED), eq(SeverityLevel.P1), any());
        // Push ran after the slow SMS attempt (this method is deliberately sequential, not
        // parallel -- see IncidentPagingService's javadoc), so this just confirms it wasn't
        // additionally delayed or skipped, not that it ran concurrently with the SMS attempt.
        assertThat(elapsedMs).isLessThan(2000L);
    }

    @Test
    void maybePage_smsFailsButPushSucceeds_stillRecordsEngineerPaged() {
        when(alertDeduplication.record(any())).thenReturn(unlinkedDedup());
        when(onCallService.pageOnCall(any(), any(), any()))
            .thenReturn(new PageResult(PageOutcome.NOT_CONFIGURED, null, "Twilio credentials not configured"));
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(true);

        pagingService.maybePage(tenantId, incidentId, "db-svc", "DB down", IncidentSeverity.CRITICAL);

        verify(timelineService).record(eq(tenantId), eq(incidentId),
            eq(IncidentTimeline.TimelineEventType.ENGINEER_PAGED), eq(SeverityLevel.P1), any());
    }

    /**
     * The test that is the whole point of this batch: force the REAL Twilio SDK call to throw
     * (not a mocked OnCallService returning a canned failure -- the actual static
     * {@code Message.creator(...).create()} call this codebase makes), through a real
     * {@code OnCallService}, and prove the timeline records {@code PAGE_FAILED}, never
     * {@code ENGINEER_PAGED}. Before Step G1, this exact scenario resulted in an
     * AuthenticationException caught and logged three levels down, with ENGINEER_PAGED written
     * unconditionally regardless.
     */
    @Test
    void maybePage_realOnCallServiceWithTwilioThrowing_recordsPageFailed() {
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

        IncidentPagingService pagingServiceWithRealOnCall =
            new IncidentPagingService(realOnCallService, alertDeduplication, timelineService, pushNotificationService);

        when(alertDeduplication.record(any())).thenReturn(unlinkedDedup());
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(false);

        try (MockedStatic<Message> mockedMessage = mockStatic(Message.class)) {
            MessageCreator creatorMock = mock(MessageCreator.class);
            mockedMessage.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), anyString()))
                .thenReturn(creatorMock);
            when(creatorMock.create()).thenThrow(new ApiException("Twilio rejected this request"));

            pagingServiceWithRealOnCall.maybePage(tenantId, incidentId, "db-svc", "DB down", IncidentSeverity.CRITICAL);
        }

        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(timelineService).record(eq(tenantId), eq(incidentId),
            eq(IncidentTimeline.TimelineEventType.PAGE_FAILED), eq(SeverityLevel.P1), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).contains("PROVIDER_ERROR");
        verify(timelineService, never()).record(any(), any(), eq(IncidentTimeline.TimelineEventType.ENGINEER_PAGED), any(SeverityLevel.class), any());
    }
}
