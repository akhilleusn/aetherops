package com.aetherops.slack;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.model.UpdateIncidentRequest;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.service.IncidentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
public class SlackInteractivityController {

    private final ObjectMapper objectMapper;
    private final SlackWorkspaceRepository workspaceRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentService incidentService;
    private final SlackApiClient slackApiClient;

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
        String actionId = action.path("action_id").asText("");

        switch (actionId) {
            case "ack_incident" -> {
                incidentService.update(incidentId, new UpdateIncidentRequest(null, null, IncidentStatus.INVESTIGATING, null, null));
                notify(responseUrl, "✅ Acknowledged by " + username);
            }
            case "resolve_incident" -> {
                incidentService.update(incidentId, new UpdateIncidentRequest(null, null, IncidentStatus.RESOLVED, null, null));
                notify(responseUrl, "✅ Resolved by " + username);
            }
            default -> log.warn("Unhandled Slack action_id={}", actionId);
        }

        return ResponseEntity.ok().build();
    }

    private void notify(String responseUrl, String text) {
        if (responseUrl != null) {
            slackApiClient.respondToInteraction(responseUrl, text);
        }
    }
}
