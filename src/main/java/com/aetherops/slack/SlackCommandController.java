package com.aetherops.slack;

import com.aetherops.oncall.OnCallRotation;
import com.aetherops.oncall.OnCallRotationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@Slf4j
public class SlackCommandController {

    private final SlackWorkspaceRepository workspaceRepository;
    private final OnCallRotationRepository rotationRepository;
    private final String dashboardBaseUrl;

    public SlackCommandController(
            SlackWorkspaceRepository workspaceRepository,
            OnCallRotationRepository rotationRepository,
            @Value("${app.dashboard-base-url:http://localhost:8080}") String dashboardBaseUrl) {
        this.workspaceRepository = workspaceRepository;
        this.rotationRepository = rotationRepository;
        this.dashboardBaseUrl = dashboardBaseUrl;
    }

    @PostMapping(value = "/slack/commands", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> handle(
            @RequestParam("team_id") String teamId,
            @RequestParam("command") String command,
            @RequestParam(value = "text", required = false, defaultValue = "") String text,
            @RequestParam(value = "response_url", required = false) String responseUrl,
            @RequestParam(value = "user_id", required = false, defaultValue = "") String slackUserId,
            @RequestParam(value = "user_name", required = false, defaultValue = "someone") String slackUsername) {

        SlackWorkspace workspace = workspaceRepository.findByTeamId(teamId).orElse(null);
        if (workspace == null) {
            return ephemeral("This Slack workspace isn't connected to an AetherOps tenant.");
        }

        String serviceId = text.trim().split("\\s+", 2)[0];
        if (serviceId.isBlank()) {
            return ephemeral("Usage: " + command + " <service-id> [message]");
        }

        UUID tenantId = workspace.getTenantId();
        return switch (command) {
            case "/oncall" -> handleOnCall(tenantId, serviceId);
            case "/page" -> handlePage(teamId, serviceId, slackUsername, slackUserId);
            default -> ephemeral("Unrecognized command: " + command);
        };
    }

    private Map<String, String> handleOnCall(UUID tenantId, String serviceId) {
        OnCallRotation rotation = rotationRepository.findByTenantIdAndServiceId(tenantId, serviceId);
        if (rotation == null) {
            return ephemeral("No on-call rotation found for `" + serviceId + "`.");
        }
        String phone = rotation.currentOnCallPhoneNumber();
        if (phone == null) {
            return ephemeral("No on-call engineer currently scheduled for `" + serviceId + "`.");
        }
        return ephemeral("On-call for `" + serviceId + "`: " + mask(phone));
    }

    /*
     * Step H1 Part 2 (see STEP_REPORT.md): removed entirely rather than gated behind a feature
     * flag. Paging trips a real SMS to an on-call engineer's phone with no undo, and unlike
     * ack/resolve there is no REST equivalent whose role check this could defer to -- there is
     * no Slack-user-to-AetherOps-user mapping in this codebase to check against at all (see
     * STEP_REPORT.md's Option C for the real fix). A feature flag only bounds the blast radius
     * for a tenant that remembers to flip it and still leaves the command live, by default, for
     * every unauthenticated member of every other connected workspace -- a half-gated command is
     * worse than an absent one. Logged (Slack identity + service) for audit visibility even
     * though the command is not executed.
     */
    private Map<String, String> handlePage(String teamId, String serviceId, String slackUsername, String slackUserId) {
        log.warn("Rejected /page via Slack -- command removed, no SMS sent. teamId={} serviceId={} slackUser={} ({})",
            teamId, serviceId, slackUsername, slackUserId);
        return ephemeral("Paging via Slack has been removed. Page on-call from the AetherOps dashboard: " + dashboardBaseUrl);
    }

    private static String mask(String phoneNumber) {
        if (phoneNumber.length() <= 4) return "••••";
        return "•".repeat(phoneNumber.length() - 4) + phoneNumber.substring(phoneNumber.length() - 4);
    }

    private static Map<String, String> ephemeral(String text) {
        return Map.of("response_type", "ephemeral", "text", text);
    }
}
