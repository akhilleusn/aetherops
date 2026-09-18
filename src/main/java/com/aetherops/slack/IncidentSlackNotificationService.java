package com.aetherops.slack;

import com.aetherops.incident.model.Incident;
import com.aetherops.tenant.TenantContext;
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
    private final IncidentSlackMetadataUpdater metadataUpdater;

    @Async("slackExecutor")
    public void notify(Incident incident) {
        // Step H2 (see STEP_REPORT.md): bug found while implementing, not part of the original
        // finding -- this method was already @Async (on the shared default pool) before this
        // change. TenantContext is a plain ThreadLocal with no propagation across an executor
        // boundary (see WebhookService.deliver's javadoc for the full explanation, and
        // TenantContextInterceptor for how RLS depends on it), so workspaceRepository's and
        // incidentRepository's RLS-protected calls below ran with app.tenant_id unset on
        // whatever thread actually executed this method. Traced through the code and the RLS
        // policy SQL, not observed against a live database (Docker/Testcontainers unavailable in
        // this session, same as every prior step).
        TenantContext.set(incident.getTenantId());
        try {
            workspaceRepository.findByTenantId(incident.getTenantId())
                .ifPresentOrElse(
                    workspace -> notifyViaBotToken(workspace, incident),
                    () -> legacyNotifier.notify(incident));
        } finally {
            TenantContext.clear();
        }
    }

    private void notifyViaBotToken(SlackWorkspace workspace, Incident incident) {
        conversationService.postIncidentAlert(workspace, incident).ifPresentOrElse(
            posted -> {
                metadataUpdater.update(incident, posted.channel(), posted.ts());
                log.info("Posted incident alert via Slack bot token incidentId={} channel={} ts={}",
                    incident.getId(), posted.channel(), posted.ts());
            },
            () -> {
                log.warn("Bot-token Slack post failed, falling back to legacy webhook incidentId={}", incident.getId());
                legacyNotifier.notify(incident);
            });
    }
}
