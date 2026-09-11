package com.aetherops.escalation;

import com.aetherops.escalation.EscalationExecution.ChannelStatus;
import com.aetherops.incident.model.Incident;
import com.aetherops.oncall.OnCallService;
import com.aetherops.oncall.OnCallService.PageResult;
import com.aetherops.push.PushNotificationService;
import com.aetherops.slack.SlackConversationService;
import com.aetherops.slack.SlackWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * {@code EscalationCheckJob} needs no changes -- it already advances escalation levels purely
 * on elapsed time, blind to delivery outcome, so a failed channel here was never what gated the
 * next level; only the *recording* of what happened was missing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscalationService {

    private final EscalationExecutionRepository executionRepository;
    private final OnCallService onCallService;
    private final PushNotificationService pushNotificationService;
    private final SlackWorkspaceRepository slackWorkspaceRepository;
    private final SlackConversationService slackConversationService;

    @Transactional
    public void escalate(Incident incident, EscalationPolicy policy, int level) {
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

        executionRepository.save(execution);
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
