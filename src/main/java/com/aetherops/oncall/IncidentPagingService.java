package com.aetherops.oncall;

import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.oncall.OnCallService.PageResult;
import com.aetherops.push.PushNotificationService;
import com.aetherops.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Entry point every incident-creation path (manual, log-grouped, monitoring-alert-ingested)
 * calls to trigger paging. Previously OnCallService.pageOnCall() was only ever invoked from
 * the manual Slack /page command — paging never fired automatically off a real incident.
 * AlertDeduplication gates repeat pages for the same flapping alert. Pages both the on-call
 * phone (SMS, Phase 1) and any registered mobile devices for the tenant (push, Phase 6).
 *
 * <p>Step G1 (see STEP_REPORT.md): {@code @Async} moved here from {@code OnCallService.pageOnCall}
 * -- callers of this method (incident ingestion) shouldn't block on Twilio/Expo HTTP latency, but
 * the outcome must still be recorded by whichever method owns firing the call, not swallowed by a
 * void async method further down with nothing above it able to observe the result. SMS and push
 * fire in parallel here (unlike {@code EscalationService}'s sequential SMS→push→Slack fallback)
 * -- a deliberate scope decision for this batch, not an oversight: this is the very first,
 * immediate page on incident creation, not an escalation step, and there's no Slack channel in
 * this path at all. Whether the two paths should converge is a separate decision.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentPagingService {

    private final OnCallService onCallService;
    private final AlertDeduplication alertDeduplication;
    private final IncidentTimelineService timelineService;
    private final PushNotificationService pushNotificationService;

    @Async("pagingExecutor")
    public void maybePage(UUID tenantId, UUID incidentId, String serviceId, String title, IncidentSeverity severity) {
        // Step H2 (see STEP_REPORT.md): bug found while implementing, not part of the original
        // finding -- this method was already @Async (on the shared default pool) before this
        // change. TenantContext is a plain ThreadLocal with no propagation across an executor
        // boundary (see WebhookService.deliver's javadoc for the full explanation), so
        // alertDeduplication's and timelineService's RLS-protected calls below ran with
        // app.tenant_id unset on whatever thread actually executed this method. Traced through
        // the code and the RLS policy SQL, not observed against a live database (Docker/
        // Testcontainers unavailable in this session, same as every prior step).
        TenantContext.set(tenantId);
        try {
            maybePageInternal(tenantId, incidentId, serviceId, title, severity);
        } finally {
            TenantContext.clear();
        }
    }

    private void maybePageInternal(UUID tenantId, UUID incidentId, String serviceId, String title, IncidentSeverity severity) {
        SeverityLevel level = SeverityLevel.fromIncidentSeverity(severity);
        if (!level.shouldPageImmediately()) {
            return;
        }

        DeduplicatedAlert dedup = alertDeduplication.record(
            new AlertDeduplication.Alert(tenantId, serviceId, title, level));

        if (dedup.getIncidentId() != null) {
            log.debug("Skipping page — already paged for this alert fingerprint incidentId={}", incidentId);
            return;
        }

        alertDeduplication.linkIncident(dedup, incidentId);
        PageResult smsResult = onCallService.pageOnCall(tenantId, serviceId, title);
        boolean pushOk = pushNotificationService.sendPageAlert(tenantId, "🚨 " + severity + " incident on " + serviceId, title);

        if (smsResult.succeeded() || pushOk) {
            String via = smsResult.succeeded() && pushOk ? "SMS and push"
                : smsResult.succeeded() ? "SMS" : "push";
            timelineService.record(tenantId, incidentId, IncidentTimeline.TimelineEventType.ENGINEER_PAGED,
                level, "On-call engineer paged for " + serviceId + " (" + via + ")");
        } else {
            timelineService.record(tenantId, incidentId, IncidentTimeline.TimelineEventType.PAGE_FAILED,
                level, "Failed to page on-call engineer for " + serviceId + ": SMS "
                    + smsResult.outcome() + (smsResult.detail() != null ? " (" + smsResult.detail() + ")" : "")
                    + ", push failed");
        }
    }
}
