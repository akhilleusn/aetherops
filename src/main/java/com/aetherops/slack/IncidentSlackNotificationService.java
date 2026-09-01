package com.aetherops.slack;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Single entry point IncidentService calls to alert Slack. Prefers the bot-token path
 * (Phase 2 OAuth install) when available — it lets us capture channel/thread_ts for later
 * postmortem transcript retrieval — and falls back to the legacy incoming-webhook notifier
 * (Phase 1) for tenants that haven't connected the interactive app.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentSlackNotificationService {

    private final SlackWorkspaceRepository workspaceRepository;
    private final SlackConversationService conversationService;
    private final SlackNotifier legacyNotifier;
    private final IncidentRepository incidentRepository;

    @Async
    public void notify(Incident incident) {
        workspaceRepository.findByTenantId(incident.getTenantId())
            .ifPresentOrElse(
                workspace -> notifyViaBotToken(workspace, incident),
                () -> legacyNotifier.notify(incident));
    }

    private void notifyViaBotToken(SlackWorkspace workspace, Incident incident) {
        conversationService.postIncidentAlert(workspace, incident).ifPresentOrElse(
            posted -> {
                incident.setSlackChannelId(posted.channel());
                incident.setSlackThreadTs(posted.ts());
                incidentRepository.save(incident);
                log.info("Posted incident alert via Slack bot token incidentId={} channel={} ts={}",
                    incident.getId(), posted.channel(), posted.ts());
            },
            () -> {
                log.warn("Bot-token Slack post failed, falling back to legacy webhook incidentId={}", incident.getId());
                legacyNotifier.notify(incident);
            });
    }
}
