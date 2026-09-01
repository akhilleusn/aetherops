package com.aetherops.slack;

import com.aetherops.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@Slf4j
public class SlackOAuthController {

    private static final String SLACK_SCOPES = "chat:write,commands,incoming-webhook,channels:history";

    private final SlackAppConfig config;
    private final SlackOAuthStateService stateService;
    private final SlackApiClient slackApiClient;
    private final SlackWorkspaceRepository workspaceRepository;
    private final SlackTokenEncryptor tokenEncryptor;
    private final String dashboardBaseUrl;

    public SlackOAuthController(
            SlackAppConfig config,
            SlackOAuthStateService stateService,
            SlackApiClient slackApiClient,
            SlackWorkspaceRepository workspaceRepository,
            SlackTokenEncryptor tokenEncryptor,
            @Value("${app.dashboard-base-url:http://localhost:8080}") String dashboardBaseUrl) {
        this.config = config;
        this.stateService = stateService;
        this.slackApiClient = slackApiClient;
        this.workspaceRepository = workspaceRepository;
        this.tokenEncryptor = tokenEncryptor;
        this.dashboardBaseUrl = dashboardBaseUrl;
    }

    @GetMapping("/api/v1/integrations/slack/install-url")
    public ResponseEntity<Map<String, String>> installUrl() {
        if (!config.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        UUID tenantId = TenantContext.get();
        String state = stateService.issue(tenantId);

        String url = UriComponentsBuilder.fromUriString("https://slack.com/oauth/v2/authorize")
                .queryParam("client_id", config.getClientId())
                .queryParam("scope", SLACK_SCOPES)
                .queryParam("redirect_uri", config.getRedirectUri())
                .queryParam("state", state)
                .build()
                .toUriString();

        return ResponseEntity.ok(Map.of("url", url));
    }

    @GetMapping("/slack/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam String code, @RequestParam String state) {
        UUID tenantId;
        try {
            tenantId = stateService.verify(state);
        } catch (IllegalArgumentException e) {
            log.warn("Slack OAuth callback rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        SlackApiClient.OAuthResult result;
        try {
            result = slackApiClient.exchangeCode(code);
        } catch (Exception e) {
            log.error("Slack OAuth code exchange failed for tenantId={}", tenantId, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }

        SlackWorkspace workspace = workspaceRepository.findByTenantId(tenantId)
                .orElseGet(() -> SlackWorkspace.builder().tenantId(tenantId).build());
        workspace.setTeamId(result.teamId());
        workspace.setTeamName(result.teamName());
        workspace.setBotUserId(result.botUserId());
        workspace.setEncryptedBotToken(tokenEncryptor.encrypt(result.accessToken()));
        workspace.setScope(result.scope());
        if (result.defaultChannelId() != null) {
            workspace.setDefaultChannelId(result.defaultChannelId());
        }
        workspaceRepository.save(workspace);

        log.info("Slack workspace connected tenantId={} teamId={}", tenantId, result.teamId());

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(dashboardBaseUrl + "/integrations.html?slack=connected"))
                .build();
    }
}
