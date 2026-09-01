package com.aetherops.escalation;

import com.aetherops.incident.model.Incident;
import com.aetherops.oncall.OnCallService;
import com.aetherops.push.PushNotificationService;
import com.aetherops.slack.SlackConversationService;
import com.aetherops.slack.SlackWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes one escalation step: records it, then re-alerts through every channel that
 * would normally only fire once per incident. Deliberately bypasses {@code IncidentPagingService}
 * (whose {@code AlertDeduplication} gate exists specifically to stop a second page for the
 * same fingerprint) — an escalation IS the second page, on purpose.
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
            .build();
        executionRepository.save(execution);

        String title = "ESCALATED (level " + level + "): " + incident.getTitle();
        onCallService.pageOnCall(incident.getTenantId(), incident.getServiceId(), title);
        pushNotificationService.sendPageAlert(incident.getTenantId(),
            "⏫ ESCALATED " + incident.getSeverity() + " incident on " + incident.getServiceId(), incident.getTitle());
        notifySlack(incident, level);

        log.warn("Escalated incidentId={} tenantId={} level={} serviceId={}",
            incident.getId(), incident.getTenantId(), level, incident.getServiceId());
    }

    private void notifySlack(Incident incident, int level) {
        if (incident.getSlackChannelId() == null) {
            return;
        }
        slackWorkspaceRepository.findByTenantId(incident.getTenantId()).ifPresent(workspace -> {
            String text = "⏫ *Still unresolved* — escalation level " + level + " for `"
                + incident.getServiceId() + "`: " + incident.getTitle();
            slackConversationService.postMessage(workspace, incident.getSlackChannelId(), text);
        });
    }
}
