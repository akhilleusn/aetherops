package com.aetherops.sla;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.slack.IncidentSlackNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlaTrackingService {

    private static final List<IncidentStatus> UNRESOLVED = List.of(IncidentStatus.OPEN, IncidentStatus.INVESTIGATING);
    private static final double AT_RISK_THRESHOLD = 0.8;

    private final IncidentRepository incidentRepository;
    private final IncidentSlackNotificationService slackNotificationService;

    public Instant responseDeadline(Incident incident) {
        return incident.getFirstSeenAt().plus(SlaPolicy.responseTarget(incident.getSeverity()));
    }

    public Instant resolutionDeadline(Incident incident) {
        return incident.getFirstSeenAt().plus(SlaPolicy.resolutionTarget(incident.getSeverity()));
    }

    public SlaState responseState(Incident incident, Instant now) {
        if (incident.getAcknowledgedAt() != null) return SlaState.MET;
        return stateFor(incident.getFirstSeenAt(), responseDeadline(incident), now);
    }

    public SlaState resolutionState(Incident incident, Instant now) {
        if (incident.getResolvedAt() != null) return SlaState.MET;
        return stateFor(incident.getFirstSeenAt(), resolutionDeadline(incident), now);
    }

    private SlaState stateFor(Instant start, Instant deadline, Instant now) {
        if (now.isAfter(deadline)) return SlaState.BREACHED;
        Duration total = Duration.between(start, deadline);
        Duration elapsed = Duration.between(start, now);
        if (total.isZero()) return SlaState.AT_RISK;
        double fraction = elapsed.toMillis() / (double) total.toMillis();
        return fraction >= AT_RISK_THRESHOLD ? SlaState.AT_RISK : SlaState.ON_TRACK;
    }

    /**
     * Scans this tenant's still-open incidents for newly-crossed SLA deadlines. Must run inside
     * a tenant context (see SlaMonitoringJob) — the incidents table is RLS-scoped to
     * app.tenant_id, so calling this without TenantContext set returns nothing to scan.
     */
    @Transactional
    public void checkAndMarkBreaches(UUID tenantId) {
        Instant now = Instant.now();
        List<Incident> unresolved = incidentRepository.findByTenantIdAndStatusIn(tenantId, UNRESOLVED);

        for (Incident incident : unresolved) {
            boolean changed = false;

            if (!incident.isResponseSlaBreached() && responseState(incident, now) == SlaState.BREACHED) {
                incident.setResponseSlaBreached(true);
                changed = true;
                log.warn("Response SLA breached incidentId={} tenantId={} severity={} deadline={}",
                    incident.getId(), tenantId, incident.getSeverity(), responseDeadline(incident));
            }
            if (!incident.isResolutionSlaBreached() && resolutionState(incident, now) == SlaState.BREACHED) {
                incident.setResolutionSlaBreached(true);
                changed = true;
                log.warn("Resolution SLA breached incidentId={} tenantId={} severity={} deadline={}",
                    incident.getId(), tenantId, incident.getSeverity(), resolutionDeadline(incident));
            }

            if (changed) {
                incidentRepository.save(incident);
                slackNotificationService.notify(incident);
            }
        }
    }

    @Transactional(readOnly = true)
    public SlaSummaryResponse getSummary(UUID tenantId, String periodParam) {
        String period = (periodParam != null && !periodParam.isBlank()) ? periodParam : YearMonth.now().toString();
        YearMonth ym = YearMonth.parse(period);
        Instant from = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Incident> incidents = incidentRepository.findByTenantAndPeriod(tenantId, from, to);
        Instant now = Instant.now();

        int responseMet = 0, responseBreached = 0, resolutionMet = 0, resolutionBreached = 0;
        long responseMinutesSum = 0, responseMinutesCount = 0;
        long resolutionMinutesSum = 0, resolutionMinutesCount = 0;

        for (Incident incident : incidents) {
            SlaState rState = responseState(incident, now);
            if (rState == SlaState.MET) {
                responseMet++;
                responseMinutesSum += Duration.between(incident.getFirstSeenAt(), incident.getAcknowledgedAt()).toMinutes();
                responseMinutesCount++;
            } else if (rState == SlaState.BREACHED) {
                responseBreached++;
            }

            SlaState resState = resolutionState(incident, now);
            if (resState == SlaState.MET) {
                resolutionMet++;
                resolutionMinutesSum += Duration.between(incident.getFirstSeenAt(), incident.getResolvedAt()).toMinutes();
                resolutionMinutesCount++;
            } else if (resState == SlaState.BREACHED) {
                resolutionBreached++;
            }
        }

        return SlaSummaryResponse.builder()
            .tenantId(tenantId)
            .period(period)
            .totalIncidents(incidents.size())
            .responseMet(responseMet)
            .responseBreached(responseBreached)
            .resolutionMet(resolutionMet)
            .resolutionBreached(resolutionBreached)
            .avgResponseMinutes(responseMinutesCount > 0 ? (double) responseMinutesSum / responseMinutesCount : null)
            .avgResolutionMinutes(resolutionMinutesCount > 0 ? (double) resolutionMinutesSum / resolutionMinutesCount : null)
            .build();
    }
}
