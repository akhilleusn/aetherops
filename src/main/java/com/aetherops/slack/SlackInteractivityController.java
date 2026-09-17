package com.aetherops.slack;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.oncall.IncidentTimeline;
import com.aetherops.oncall.IncidentTimelineService;
import com.aetherops.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Slf4j
public class SlackInteractivityController {

    private final ObjectMapper objectMapper;
    private final SlackWorkspaceRepository workspaceRepository;
    private final IncidentRepository incidentRepository;
    private final SlackApiClient slackApiClient;
    private final IncidentTimelineService timelineService;
    private final String dashboardBaseUrl;

    public SlackInteractivityController(
            ObjectMapper objectMapper,
            SlackWorkspaceRepository workspaceRepository,
            IncidentRepository incidentRepository,
            SlackApiClient slackApiClient,
            IncidentTimelineService timelineService,
            @Value("${app.dashboard-base-url:http://localhost:8080}") String dashboardBaseUrl) {
        this.objectMapper = objectMapper;
        this.workspaceRepository = workspaceRepository;
        this.incidentRepository = incidentRepository;
        this.slackApiClient = slackApiClient;
        this.timelineService = timelineService;
        this.dashboardBaseUrl = dashboardBaseUrl;
    }

    @PostMapping("/slack/interactivity")
    public ResponseEntity<Void> handle(@RequestParam("payload") String payloadJson) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        String teamId = payload.path("team").path("id").asText(null);
        String responseUrl = payload.path("response_url").asText(null);
        JsonNode actions = payload.path("actions");
        JsonNode action = actions.isArray() && !actions.isEmpty() ? actions.get(0) : null;

        if (teamId == null || action == null) {
            return ResponseEntity.badRequest().build();
        }

        SlackWorkspace workspace = workspaceRepository.findByTeamId(teamId).orElse(null);
        if (workspace == null) {
            log.warn("Slack interactivity from unknown teamId={}", teamId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        UUID incidentId;
        try {
            incidentId = UUID.fromString(action.path("value").asText(""));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        Incident incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null || !incident.getTenantId().equals(workspace.getTenantId())) {
            log.warn("Slack action for incident outside workspace tenant incidentId={} teamId={}", incidentId, teamId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String username = payload.path("user").path("username").asText("someone");
        String slackUserId = payload.path("user").path("id").asText("");
        String actionId = action.path("action_id").asText("");

        /*
         * Step H1 Part 2 (see STEP_REPORT.md): there is no Slack-user-to-AetherOps-user
         * mapping anywhere in this codebase, so this handler can only ever prove "someone in
         * the connected Slack workspace clicked this" -- never "an authorized AetherOps user
         * did." The REST equivalent of both actions (IncidentController.update,
         * PATCH /api/v1/.../incidents/{id}) requires OWNER/ADMIN, a strictly smaller population
         * than "anyone in the workspace." Acknowledging silences escalation exactly like
         * resolving does -- an unauthorized ack is a suppressed page the on-call engineer is
         * never told about, the same class of harm as an unauthorized resolve. So neither
         * action executes anymore: both redirect to the dashboard, where the existing role
         * check already applies, and both record the attempt (including the presented Slack
         * identity) to the incident timeline as an audit trail -- accountability, not an
         * authorization check. Until real per-user identity linking exists (see
         * STEP_REPORT.md's Option C), this is the ceiling of what this endpoint can safely do.
         */
        TenantContext.set(workspace.getTenantId());
        try {
            switch (actionId) {
                case "ack_incident", "resolve_incident" -> {
                    boolean isAck = actionId.equals("ack_incident");
                    String verb = isAck ? "Acknowledge" : "Resolve";
                    String gerund = isAck ? "Acknowledging" : "Resolving";
                    log.info("{} requested via Slack incidentId={} teamId={} slackUser={} ({}) "
                            + "-- not executed, redirected to the dashboard where role checks apply",
                        verb, incidentId, teamId, username, slackUserId);
                    timelineService.record(workspace.getTenantId(), incidentId,
                        IncidentTimeline.TimelineEventType.SLACK_ACTION_REQUESTED, incident.getSeverity(),
                        verb + " requested via Slack by " + username
                            + (slackUserId.isBlank() ? "" : " (" + slackUserId + ")")
                            + " -- redirected to dashboard, no state change");
                    notify(responseUrl, gerund + " an incident requires the AetherOps dashboard: "
                        + dashboardBaseUrl + "/incidents/" + incidentId);
                }
                default -> log.warn("Unhandled Slack action_id={}", actionId);
            }
        } finally {
            TenantContext.clear();
        }

        return ResponseEntity.ok().build();
    }

    private void notify(String responseUrl, String text) {
        if (responseUrl != null) {
            slackApiClient.respondToInteraction(responseUrl, text);
        }
    }
}
