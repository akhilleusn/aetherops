package com.aetherops.slack;

import com.aetherops.oncall.OnCallRotation;
import com.aetherops.oncall.OnCallRotationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
public class SlackCommandController {

    private final SlackWorkspaceRepository workspaceRepository;
    private final OnCallRotationRepository rotationRepository;
    private final SlackPageResponseService pageResponseService;

    @PostMapping(value = "/slack/commands", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> handle(
            @RequestParam("team_id") String teamId,
            @RequestParam("command") String command,
            @RequestParam(value = "text", required = false, defaultValue = "") String text,
            @RequestParam(value = "response_url", required = false) String responseUrl) {

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
            case "/page" -> handlePage(tenantId, serviceId, text, responseUrl);
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

    private Map<String, String> handlePage(UUID tenantId, String serviceId, String text, String responseUrl) {
        String message = text.trim().replaceFirst("^\\S+\\s*", "");
        pageResponseService.pageAndRespond(tenantId, serviceId, message.isBlank() ? "Paged from Slack" : message, responseUrl);
        return ephemeral("Paging on-call engineer for `" + serviceId + "`...");
    }

    private static String mask(String phoneNumber) {
        if (phoneNumber.length() <= 4) return "••••";
        return "•".repeat(phoneNumber.length() - 4) + phoneNumber.substring(phoneNumber.length() - 4);
    }

    private static Map<String, String> ephemeral(String text) {
        return Map.of("response_type", "ephemeral", "text", text);
    }
}
