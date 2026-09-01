package com.aetherops.sla;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.slack.IncidentSlackNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaTrackingServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private IncidentSlackNotificationService slackNotificationService;

    @InjectMocks
    private SlaTrackingService slaTrackingService;

    private final UUID tenantId = UUID.randomUUID();

    private Incident incident(IncidentSeverity severity, Instant firstSeenAt, Instant acknowledgedAt, Instant resolvedAt) {
        return Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).severity(severity)
            .status(resolvedAt != null ? IncidentStatus.RESOLVED : IncidentStatus.OPEN)
            .serviceId("svc").firstSeenAt(firstSeenAt).lastSeenAt(firstSeenAt)
            .acknowledgedAt(acknowledgedAt).resolvedAt(resolvedAt)
            .build();
    }

    // ── state computation ────────────────────────────────────────────────

    @Test
    void responseState_acknowledged_isMet() {
        Instant firstSeen = Instant.now().minus(Duration.ofMinutes(10));
        Incident incident = incident(IncidentSeverity.CRITICAL, firstSeen, firstSeen.plusSeconds(60), null);
        assertThat(slaTrackingService.responseState(incident, Instant.now())).isEqualTo(SlaState.MET);
    }

    @Test
    void responseState_criticalPastFiveMinutes_unacknowledged_isBreached() {
        Instant firstSeen = Instant.now().minus(Duration.ofMinutes(10));
        Incident incident = incident(IncidentSeverity.CRITICAL, firstSeen, null, null);
        assertThat(slaTrackingService.responseState(incident, Instant.now())).isEqualTo(SlaState.BREACHED);
    }

    @Test
    void responseState_criticalWellWithinWindow_isOnTrack() {
        Instant firstSeen = Instant.now().minus(Duration.ofSeconds(30));
        Incident incident = incident(IncidentSeverity.CRITICAL, firstSeen, null, null);
        assertThat(slaTrackingService.responseState(incident, Instant.now())).isEqualTo(SlaState.ON_TRACK);
    }

    @Test
    void responseState_criticalNearDeadline_isAtRisk() {
        // 5-min target, 80% threshold -> at-risk starts at 4 minutes elapsed
        Instant firstSeen = Instant.now().minus(Duration.ofMinutes(4).plusSeconds(30));
        Incident incident = incident(IncidentSeverity.CRITICAL, firstSeen, null, null);
        assertThat(slaTrackingService.responseState(incident, Instant.now())).isEqualTo(SlaState.AT_RISK);
    }

    @Test
    void resolutionState_resolved_isMet() {
        Instant firstSeen = Instant.now().minus(Duration.ofHours(2));
        Incident incident = incident(IncidentSeverity.HIGH, firstSeen, firstSeen, firstSeen.plusSeconds(3600));
        assertThat(slaTrackingService.resolutionState(incident, Instant.now())).isEqualTo(SlaState.MET);
    }

    @Test
    void resolutionState_highPastEightHours_unresolved_isBreached() {
        Instant firstSeen = Instant.now().minus(Duration.ofHours(9));
        Incident incident = incident(IncidentSeverity.HIGH, firstSeen, firstSeen, null);
        assertThat(slaTrackingService.resolutionState(incident, Instant.now())).isEqualTo(SlaState.BREACHED);
    }

    @Test
    void lowSeverity_hasLoosestTargets() {
        Instant firstSeen = Instant.now().minus(Duration.ofHours(1));
        Incident incident = incident(IncidentSeverity.LOW, firstSeen, null, null);
        // LOW response target is 4 hours; 1 hour elapsed should still be on track
        assertThat(slaTrackingService.responseState(incident, Instant.now())).isEqualTo(SlaState.ON_TRACK);
    }

    // ── checkAndMarkBreaches ─────────────────────────────────────────────

    @Test
    void checkAndMarkBreaches_newlyBreachedIncident_marksFlagAndNotifiesSlack() {
        Instant firstSeen = Instant.now().minus(Duration.ofMinutes(10));
        Incident incident = incident(IncidentSeverity.CRITICAL, firstSeen, null, null);
        when(incidentRepository.findByTenantIdAndStatusIn(eq(tenantId), any())).thenReturn(List.of(incident));
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        slaTrackingService.checkAndMarkBreaches(tenantId);

        assertThat(incident.isResponseSlaBreached()).isTrue();
        verify(incidentRepository).save(incident);
        verify(slackNotificationService).notify(incident);
    }

    @Test
    void checkAndMarkBreaches_alreadyBreachedIncident_doesNotReAlert() {
        Instant firstSeen = Instant.now().minus(Duration.ofMinutes(10));
        Incident incident = incident(IncidentSeverity.CRITICAL, firstSeen, null, null);
        incident.setResponseSlaBreached(true);
        when(incidentRepository.findByTenantIdAndStatusIn(eq(tenantId), any())).thenReturn(List.of(incident));

        slaTrackingService.checkAndMarkBreaches(tenantId);

        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(slackNotificationService);
    }

    @Test
    void checkAndMarkBreaches_incidentOnTrack_doesNothing() {
        Instant firstSeen = Instant.now().minus(Duration.ofSeconds(10));
        Incident incident = incident(IncidentSeverity.CRITICAL, firstSeen, null, null);
        when(incidentRepository.findByTenantIdAndStatusIn(eq(tenantId), any())).thenReturn(List.of(incident));

        slaTrackingService.checkAndMarkBreaches(tenantId);

        verify(incidentRepository, never()).save(any());
    }

    // ── summary ──────────────────────────────────────────────────────────

    @Test
    void getSummary_countsMetAndBreachedCorrectly() {
        String period = YearMonth.now().toString();
        Instant now = Instant.now();
        Incident met = incident(IncidentSeverity.LOW, now.minus(Duration.ofHours(1)), now, now);
        Incident breached = incident(IncidentSeverity.CRITICAL, now.minus(Duration.ofMinutes(20)), null, null);

        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any()))
            .thenReturn(List.of(met, breached));

        SlaSummaryResponse summary = slaTrackingService.getSummary(tenantId, period);

        assertThat(summary.getTotalIncidents()).isEqualTo(2);
        assertThat(summary.getResponseMet()).isEqualTo(1);
        assertThat(summary.getResponseBreached()).isEqualTo(1);
    }

    @Test
    void getSummary_noResolvedIncidents_avgResolutionMinutesIsNull() {
        String period = YearMonth.now().toString();
        Incident open = incident(IncidentSeverity.MEDIUM, Instant.now().minus(Duration.ofMinutes(5)), null, null);
        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any())).thenReturn(List.of(open));

        SlaSummaryResponse summary = slaTrackingService.getSummary(tenantId, period);

        assertThat(summary.getAvgResolutionMinutes()).isNull();
    }
}
