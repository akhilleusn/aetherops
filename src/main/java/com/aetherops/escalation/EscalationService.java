package com.aetherops.escalation;

import com.aetherops.escalation.EscalationExecution.ChannelStatus;
import com.aetherops.incident.model.Incident;
import com.aetherops.oncall.OnCallService;
import com.aetherops.oncall.OnCallService.PageResult;
import com.aetherops.push.PushNotificationService;
import com.aetherops.slack.SlackConversationService;
import com.aetherops.slack.SlackWorkspaceRepository;
import com.aetherops.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Executes one escalation step: records it, then re-alerts through channels in order --
 * SMS, then push, then Slack -- stopping at the first one the provider accepts. Deliberately
 * bypasses {@code IncidentPagingService} (whose {@code AlertDeduplication} gate exists
 * specifically to stop a second page for the same fingerprint) — an escalation IS the second
 * page, on purpose.
 *
 * <p>Step G1 (see STEP_REPORT.md): previously fired all three channels unconditionally,
 * regardless of whether any of them actually worked, and recorded nothing about the outcome.
 * Now sequential-stop-on-success (paging someone three times for one incident is noise, not
 * redundancy) with every attempt's result persisted on {@link EscalationExecution}.
 *
 * <p>Step H2 (see STEP_REPORT.md): this used to run synchronously on whatever thread called it
 * -- which, via {@code EscalationCheckJob}, was Spring Boot's single default {@code @Scheduled}
 * thread. No {@code TaskScheduler} bean or {@code spring.task.scheduling.*} property exists
 * anywhere in this codebase, so that one thread also runs every other scheduled job in the app
 * ({@code OutboxService}, {@code SlaMonitoringJob}, {@code WarRoomAutoOpenJob}, this class's own
 * {@code IncidentReportService}). A hang in SMS/push/Slack here didn't just stall escalation --
 * it stopped outbox processing and SLA monitoring too. Now {@code @Async("pagingExecutor")}, so
 * {@code EscalationCheckJob}'s sweep dispatches each incident's escalation and moves on
 * immediately; {@code EscalationCheckJob} itself needed no change; this method just now runs on
 * its own thread from the same small pool paging uses (its own priority, isolated from Jira/
 * ServiceNow/Slack-notification/webhook traffic).
 *
 * <p>{@code @Transactional} deliberately dropped rather than kept alongside {@code @Async}: this
 * method is invoked via a bean reference from {@code EscalationCheckJob}, which already sets
 * {@code TenantContext} on ITS thread before calling in -- but {@code TenantContext} is a plain
 * ThreadLocal, so once {@code @Async} hops this call to a different thread, that value doesn't
 * follow. Setting it again as this method's own first line only works reliably if nothing else
 * needs it set any earlier in the same method's proxy chain -- true for a bare {@code @Async}
 * method, not guaranteed for one that's also {@code @Transactional} (whose advice would run
 * before this method's body, and Spring does not document a stable relative order for
 * {@code @Async} vs {@code @Transactional} advisors on the same method). Every DB write below --
 * everything inside {@code onCallService}/{@code pushNotificationService}/
 * {@code slackConversationService}, and this class's own write via
 * {@link EscalationExecutionRecorder#save} -- goes through its own bean's {@code @Transactional}
 * method via a genuine cross-bean call, invoked strictly after this method's
 * {@code TenantContext.set}, so correctness doesn't depend on this method having its own
 * transaction boundary.
 *
 * <p>Recon finding (see STEP_REPORT.md): this javadoc previously claimed the execution write was
 * already "transactional on its own bean" -- it wasn't. {@code executionRepository.save(execution)}
 * was called directly from {@code escalateInternal}, a private method self-invoked from
 * {@code escalate}; self-invocation bypasses the Spring AOP proxy entirely, so that write never
 * ran inside a transaction {@code TenantContextInterceptor} could act on, the same gap fixed this
 * morning in {@code AlertDeduplication.linkIncident} and
 * {@code IncidentSlackNotificationService.notifyViaBotToken}. Fixed by moving the write into
 * {@link EscalationExecutionRecorder}, a real separate bean reached by a genuine cross-bean call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscalationService {

    private final EscalationExecutionRecorder executionRecorder;
    private final OnCallService onCallService;
    private final PushNotificationService pushNotificationService;
    private final SlackWorkspaceRepository slackWorkspaceRepository;
    private final SlackConversationService slackConversationService;

    @Async("pagingExecutor")
    public void escalate(Incident incident, EscalationPolicy policy, int level) {
        TenantContext.set(incident.getTenantId());
        try {
            escalateInternal(incident, policy, level);
        } finally {
            TenantContext.clear();
        }
    }

    private void escalateInternal(Incident incident, EscalationPolicy policy, int level) {
        EscalationExecution execution = EscalationExecution.builder()
            .tenantId(incident.getTenantId())
            .incidentId(incident.getId())
            .policyId(policy.getId())
            .escalationLevel(level)
            .smsStatus(ChannelStatus.NOT_ATTEMPTED)
            .pushStatus(ChannelStatus.NOT_ATTEMPTED)
            .slackStatus(ChannelStatus.NOT_ATTEMPTED)
            .build();

        String title = "ESCALATED (level " + level + "): " + incident.getTitle();

        PageResult smsResult = onCallService.pageOnCall(incident.getTenantId(), incident.getServiceId(), title);
        execution.setSmsStatus(smsResult.succeeded() ? ChannelStatus.ACCEPTED : ChannelStatus.FAILED);
        execution.setSmsDetail(describe(smsResult));

        if (!smsResult.succeeded()) {
            boolean pushOk = pushNotificationService.sendPageAlert(incident.getTenantId(),
                "⏫ ESCALATED " + incident.getSeverity() + " incident on " + incident.getServiceId(), incident.getTitle());
            execution.setPushStatus(pushOk ? ChannelStatus.ACCEPTED : ChannelStatus.FAILED);

            if (!pushOk) {
                execution.setSlackStatus(notifySlack(incident, level));
            }
        }

        executionRecorder.save(execution);
        log.warn("Escalated incidentId={} tenantId={} level={} serviceId={} sms={} push={} slack={}",
            incident.getId(), incident.getTenantId(), level, incident.getServiceId(),
            execution.getSmsStatus(), execution.getPushStatus(), execution.getSlackStatus());
    }

    private String describe(PageResult result) {
        return result.detail() != null ? result.outcome() + ": " + result.detail() : result.outcome().toString();
    }

    /** Returns NOT_ATTEMPTED (not FAILED) when there's nothing to attempt against -- no Slack
     *  channel on this incident, or no workspace connected for this tenant -- since a channel
     *  that was never tried was never tried, per EscalationExecution.ChannelStatus's javadoc. */
    private ChannelStatus notifySlack(Incident incident, int level) {
        if (incident.getSlackChannelId() == null) {
            return ChannelStatus.NOT_ATTEMPTED;
        }
        return slackWorkspaceRepository.findByTenantId(incident.getTenantId())
            .map(workspace -> {
                String text = "⏫ *Still unresolved* — escalation level " + level + " for `"
                    + incident.getServiceId() + "`: " + incident.getTitle();
                boolean posted = slackConversationService.postMessage(workspace, incident.getSlackChannelId(), text).isPresent();
                return posted ? ChannelStatus.ACCEPTED : ChannelStatus.FAILED;
            })
            .orElse(ChannelStatus.NOT_ATTEMPTED);
    }
}
