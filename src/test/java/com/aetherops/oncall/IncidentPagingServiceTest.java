package com.aetherops.oncall;

import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.push.PushNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private DeduplicatedAlert unlinkedDedup() {
        return DeduplicatedAlert.builder().tenantId(tenantId).alertCount(1).incidentId(null).build();
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
}
