package com.aetherops.integrations.controller;

import com.aetherops.apikey.model.ApiKey;
import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.integrations.model.*;
import com.aetherops.integrations.webhook.AwsSnsHostValidator;
import com.aetherops.integrations.webhook.SnsSignatureVerifier;
import com.aetherops.monitoring.service.MonitoringAlertIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Alert-source and deploy-event webhook receivers for the Phase 7 integrations batch. Each
 * provider maps its own payload shape + severity vocabulary into
 * {@link MonitoringAlertIngestionService.AlertSignal} and delegates to the same
 * find-or-create-by-fingerprint ingestion path the Datadog/Alertmanager integrations use
 * (see com.aetherops.monitoring) — this controller intentionally does not touch that
 * package's files, it only calls its public service.
 */
@RestController
@RequestMapping("/api/v1/integrations")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Integrations", description = "Alert-source and deploy-event webhooks: Grafana, New Relic, Splunk, Sentry, Rollbar, Sumo Logic, CloudWatch, Opsgenie, VictorOps, PagerDuty, GitHub")
public class IntegrationsWebhookController {

    private final ApiKeyService apiKeyService;
    private final MonitoringAlertIngestionService ingestionService;
    private final RestClient.Builder restClientBuilder;
    private final SnsSignatureVerifier snsSignatureVerifier;

    @PostMapping("/grafana")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Grafana unified alerting webhook (Alertmanager-compatible)")
    public ResponseEntity<Void> grafana(@RequestBody GrafanaWebhookRequest request,
                                         @RequestHeader("X-Api-Key") String apiKeyHeader) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);
        List<GrafanaWebhookRequest.Alert> alerts = request.getAlerts() != null ? request.getAlerts() : List.of();

        if (alerts.isEmpty()) {
            log.debug("Grafana webhook with no per-alert entries, status={}", request.getStatus());
            return ResponseEntity.accepted().build();
        }

        for (GrafanaWebhookRequest.Alert alert : alerts) {
            Map<String, String> labels = alert.getLabels() != null ? alert.getLabels() : Map.of();
            Map<String, String> annotations = alert.getAnnotations() != null ? alert.getAnnotations() : Map.of();

            String alertName = labels.getOrDefault("alertname", "unknown_alert");
            String serviceId = labels.getOrDefault("service", labels.getOrDefault("job", "unknown-service"));
            String summary = annotations.getOrDefault("summary", alertName);
            String description = annotations.getOrDefault("description", summary);
            boolean firing = "firing".equalsIgnoreCase(alert.getStatus() != null ? alert.getStatus() : request.getStatus());

            ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
                apiKey.getTenantId(), apiKey.getProjectId(), serviceId, alertName,
                summary, description, alertmanagerStyleSeverity(labels.get("severity")), firing,
                labels.get("environment"), alert.getStartsAt() != null ? alert.getStartsAt() : Instant.now()));
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/new-relic")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "New Relic alerts/applied-intelligence workflow webhook")
    public ResponseEntity<Void> newRelic(@RequestBody NewRelicWebhookRequest request,
                                          @RequestHeader("X-Api-Key") String apiKeyHeader) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);
        String serviceId = "unknown-service";
        if (request.getEntitiesData() != null && request.getEntitiesData().getEntities() != null
            && !request.getEntitiesData().getEntities().isEmpty()) {
            String name = request.getEntitiesData().getEntities().get(0).getName();
            if (name != null && !name.isBlank()) {
                serviceId = name;
            }
        }
        boolean firing = "ACTIVATED".equalsIgnoreCase(request.getState());

        ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
            apiKey.getTenantId(), apiKey.getProjectId(), serviceId, request.getIssueId(),
            request.getTitle(), request.getTitle(), newRelicSeverity(request.getPriority()), firing,
            null, Instant.now()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/splunk")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Splunk alert action webhook")
    public ResponseEntity<Void> splunk(@Valid @RequestBody SplunkWebhookRequest request,
                                        @RequestHeader("X-Api-Key") String apiKeyHeader) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);

        ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
            apiKey.getTenantId(), apiKey.getProjectId(), request.getSearch_name(), request.getSearch_name(),
            request.getSearch_name(), "Splunk saved search triggered: " + request.getSearch_name(),
            splunkSeverity(request.getSeverity()), true, null, Instant.now()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/sentry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Sentry issue-alert webhook")
    public ResponseEntity<Void> sentry(@RequestBody SentryWebhookRequest request,
                                        @RequestHeader("X-Api-Key") String apiKeyHeader) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);
        SentryWebhookRequest.Issue issue = request.getData() != null ? request.getData().getIssue() : null;
        String title = issue != null && issue.getTitle() != null ? issue.getTitle() : "Sentry issue";
        String serviceId = issue != null && issue.getProject() != null ? issue.getProject() : "unknown-service";
        boolean firing = !"resolved".equalsIgnoreCase(request.getAction());

        ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
            apiKey.getTenantId(), apiKey.getProjectId(), serviceId, title,
            title, issue != null ? issue.getCulprit() : title,
            sentrySeverity(issue != null ? issue.getLevel() : null), firing, null, Instant.now()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/rollbar")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Rollbar notification webhook")
    public ResponseEntity<Void> rollbar(@RequestBody RollbarWebhookRequest request,
                                         @RequestHeader("X-Api-Key") String apiKeyHeader) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);
        RollbarWebhookRequest.Item item = request.getData() != null ? request.getData().getItem() : null;
        String title = item != null && item.getTitle() != null ? item.getTitle() : "Rollbar item";
        String serviceId = item != null && item.getProject() != null && item.getProject().getName() != null
            ? item.getProject().getName() : "unknown-service";
        boolean firing = request.getEvent_name() == null || !request.getEvent_name().toLowerCase(Locale.ROOT).contains("resolved");

        ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
            apiKey.getTenantId(), apiKey.getProjectId(), serviceId, title,
            title, title, rollbarSeverity(item != null ? item.getLevel() : null), firing, null, Instant.now()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/sumo-logic")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Sumo Logic scheduled-search webhook (customer-templated payload)")
    public ResponseEntity<Void> sumoLogic(@Valid @RequestBody SumoLogicWebhookRequest request,
                                           @RequestHeader("X-Api-Key") String apiKeyHeader) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);

        ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
            apiKey.getTenantId(), apiKey.getProjectId(), request.getServiceId(), request.getAlertName(),
            request.getAlertName(), request.getDescription(), genericSeverity(request.getSeverity()),
            request.isFiring(), null, Instant.now()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/opsgenie")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Opsgenie outgoing webhook integration")
    public ResponseEntity<Void> opsgenie(@RequestBody OpsgenieWebhookRequest request,
                                          @RequestHeader("X-Api-Key") String apiKeyHeader) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);
        OpsgenieWebhookRequest.Alert alert = request.getAlert();
        String message = alert != null && alert.getMessage() != null ? alert.getMessage() : "Opsgenie alert";
        String serviceId = alert != null && alert.getSource() != null ? alert.getSource() : "unknown-service";
        boolean firing = !"Close".equalsIgnoreCase(request.getAction());

        ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
            apiKey.getTenantId(), apiKey.getProjectId(), serviceId, message,
            message, message, priorityScaleSeverity(alert != null ? alert.getPriority() : null),
            firing, null, Instant.now()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/victorops")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "VictorOps (Splunk On-Call) outbound webhook")
    public ResponseEntity<Void> victorOps(@RequestBody VictorOpsWebhookRequest request,
                                           @RequestHeader("X-Api-Key") String apiKeyHeader) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);
        String serviceId = request.getEntity_display_name() != null ? request.getEntity_display_name()
            : (request.getEntity_id() != null ? request.getEntity_id() : "unknown-service");
        String alertName = request.getEntity_id() != null ? request.getEntity_id() : serviceId;
        String messageType = request.getMessage_type() != null ? request.getMessage_type().toUpperCase(Locale.ROOT) : "";
        boolean firing = !"RECOVERY".equals(messageType);

        ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
            apiKey.getTenantId(), apiKey.getProjectId(), serviceId, alertName,
            request.getState_message() != null ? request.getState_message() : alertName,
            request.getState_message(), victorOpsSeverity(messageType), firing, null, Instant.now()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/pagerduty")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "PagerDuty webhook subscriptions v3")
    public ResponseEntity<Void> pagerDuty(@RequestBody PagerDutyWebhookRequest request,
                                           @RequestHeader("X-Api-Key") String apiKeyHeader) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);
        PagerDutyWebhookRequest.Event event = request.getEvent();
        PagerDutyWebhookRequest.EventData data = event != null ? event.getData() : null;
        String title = data != null && data.getTitle() != null ? data.getTitle() : "PagerDuty incident";
        String serviceId = data != null && data.getService() != null && data.getService().getSummary() != null
            ? data.getService().getSummary() : "unknown-service";
        String eventType = event != null ? event.getEvent_type() : "";
        boolean firing = !"incident.resolved".equalsIgnoreCase(eventType);
        String priority = data != null && data.getPriority() != null ? data.getPriority().getSummary() : null;

        ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
            apiKey.getTenantId(), apiKey.getProjectId(), serviceId, title,
            title, title, priorityScaleSeverity(priority), firing, null, Instant.now()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/cloudwatch")
    @Operation(summary = "AWS CloudWatch Alarms via an SNS HTTP(S) subscription")
    public ResponseEntity<Void> cloudWatch(@RequestBody CloudWatchSnsRequest request,
                                            @RequestHeader("X-Api-Key") String apiKeyHeader) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);

        // AWS signs every SNS message unconditionally (unlike GitHub/PagerDuty/Grafana this
        // isn't a per-tenant opt-in secret), so verification here is mandatory, not
        // conditional. See SnsSignatureVerifier's javadoc for the SigningCertURL-spoofing
        // mistake this specifically closes.
        if (!snsSignatureVerifier.verify(request)) {
            log.warn("Rejected CloudWatch/SNS webhook with invalid or missing signature tenantId={}",
                apiKey.getTenantId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if ("SubscriptionConfirmation".equals(request.getType())) {
            confirmSnsSubscription(request.getSubscribeURL());
            return ResponseEntity.ok().build();
        }

        if (!"Notification".equals(request.getType()) || request.getMessage() == null) {
            return ResponseEntity.accepted().build();
        }

        CloudWatchAlarmMessage alarm = parseAlarmMessage(request.getMessage());
        if (alarm == null || alarm.getAlarmName() == null) {
            log.warn("Could not parse CloudWatch alarm message tenantId={}", apiKey.getTenantId());
            return ResponseEntity.accepted().build();
        }

        boolean firing = "ALARM".equalsIgnoreCase(alarm.getNewStateValue());
        // CloudWatch alarms don't carry a severity field; tenants needing finer control should
        // route through SNS -> Lambda -> a severity-aware source instead. HIGH is a deliberately
        // simple, conservative default so an unclassified alarm still pages rather than being ignored.
        ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
            apiKey.getTenantId(), apiKey.getProjectId(), alarm.getAlarmName(), alarm.getAlarmName(),
            alarm.getAlarmName(), alarm.getNewStateReason(), IncidentSeverity.HIGH, firing, null, Instant.now()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/github")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "GitHub deployment_status webhook event — alerts on deploy failures")
    public ResponseEntity<Void> gitHub(@RequestBody GitHubDeploymentStatusRequest request,
                                        @RequestHeader("X-Api-Key") String apiKeyHeader,
                                        @RequestHeader(value = "X-GitHub-Event", required = false) String eventType) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);

        if (!"deployment_status".equals(eventType)) {
            return ResponseEntity.accepted().build();
        }

        GitHubDeploymentStatusRequest.DeploymentStatus status = request.getDeployment_status();
        String state = status != null ? status.getState() : null;
        if (state == null) {
            return ResponseEntity.accepted().build();
        }

        String repo = request.getRepository() != null ? request.getRepository().getFull_name() : "unknown-repo";
        String environment = request.getDeployment() != null ? request.getDeployment().getEnvironment() : null;
        String sha = request.getDeployment() != null ? request.getDeployment().getSha() : null;
        String alertName = "deploy:" + repo + ":" + environment;

        if (state.equalsIgnoreCase("failure") || state.equalsIgnoreCase("error")) {
            String title = "Deployment failed: " + repo + " (" + environment + ")";
            String description = sha != null ? "Commit " + sha + " failed to deploy" : title;
            ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
                apiKey.getTenantId(), apiKey.getProjectId(), repo, alertName,
                title, description, IncidentSeverity.HIGH, true, environment, Instant.now()));
        } else if (state.equalsIgnoreCase("success")) {
            ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
                apiKey.getTenantId(), apiKey.getProjectId(), repo, alertName,
                "Deployment succeeded: " + repo + " (" + environment + ")", null,
                IncidentSeverity.HIGH, false, environment, Instant.now()));
        }
        // pending/in_progress/queued: no-op, wait for a terminal state.

        return ResponseEntity.accepted().build();
    }

    /** Real AWS SNS confirmation URLs are always https://sns.{region}.amazonaws.com/... (or the .amazonaws.com.cn
     *  China partition) — anything else is rejected before it's fetched, since this is a server-side request to a
     *  caller-supplied URL and only an X-Api-Key (not a privileged credential) gates who can trigger it.
     *  Shared with SnsSignatureVerifier's identical SigningCertURL check via AwsSnsHostValidator. */
    private void confirmSnsSubscription(String subscribeUrl) {
        if (subscribeUrl == null || subscribeUrl.isBlank()) {
            return;
        }
        if (!AwsSnsHostValidator.isValidSnsHost(subscribeUrl)) {
            log.warn("Refusing to confirm SNS subscription — URL does not match an AWS SNS endpoint: {}", subscribeUrl);
            return;
        }
        try {
            restClientBuilder.build().get().uri(subscribeUrl).retrieve().toBodilessEntity();
            log.info("Confirmed SNS subscription");
        } catch (Exception e) {
            log.warn("Failed to confirm SNS subscription at {}", subscribeUrl, e);
        }
    }

    private CloudWatchAlarmMessage parseAlarmMessage(String message) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(message, CloudWatchAlarmMessage.class);
        } catch (Exception e) {
            return null;
        }
    }

    private IncidentSeverity alertmanagerStyleSeverity(String severityLabel) {
        if (severityLabel == null) return IncidentSeverity.MEDIUM;
        return switch (severityLabel.toLowerCase(Locale.ROOT)) {
            case "critical", "page" -> IncidentSeverity.CRITICAL;
            case "warning" -> IncidentSeverity.HIGH;
            case "info" -> IncidentSeverity.LOW;
            default -> IncidentSeverity.MEDIUM;
        };
    }

    private IncidentSeverity newRelicSeverity(String priority) {
        if (priority == null) return IncidentSeverity.MEDIUM;
        return switch (priority.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> IncidentSeverity.CRITICAL;
            case "HIGH" -> IncidentSeverity.HIGH;
            case "LOW" -> IncidentSeverity.LOW;
            default -> IncidentSeverity.MEDIUM;
        };
    }

    private IncidentSeverity splunkSeverity(String severity) {
        if (severity == null) return IncidentSeverity.MEDIUM;
        return switch (severity.trim()) {
            case "1", "2" -> IncidentSeverity.CRITICAL;
            case "3" -> IncidentSeverity.HIGH;
            case "4" -> IncidentSeverity.MEDIUM;
            case "5" -> IncidentSeverity.LOW;
            default -> IncidentSeverity.MEDIUM;
        };
    }

    private IncidentSeverity sentrySeverity(String level) {
        if (level == null) return IncidentSeverity.MEDIUM;
        return switch (level.toLowerCase(Locale.ROOT)) {
            case "fatal" -> IncidentSeverity.CRITICAL;
            case "error" -> IncidentSeverity.HIGH;
            case "warning" -> IncidentSeverity.MEDIUM;
            default -> IncidentSeverity.LOW;
        };
    }

    private IncidentSeverity rollbarSeverity(String level) {
        if (level == null) return IncidentSeverity.MEDIUM;
        return switch (level.toLowerCase(Locale.ROOT)) {
            case "critical" -> IncidentSeverity.CRITICAL;
            case "error" -> IncidentSeverity.HIGH;
            case "warning" -> IncidentSeverity.MEDIUM;
            default -> IncidentSeverity.LOW;
        };
    }

    private IncidentSeverity genericSeverity(String severity) {
        if (severity == null) return IncidentSeverity.MEDIUM;
        try {
            return IncidentSeverity.valueOf(severity.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return IncidentSeverity.MEDIUM;
        }
    }

    /** P1-P5 priority scale shared by Opsgenie and PagerDuty, same mapping as Datadog's P1-P5 handling. */
    private IncidentSeverity priorityScaleSeverity(String priority) {
        if (priority == null) return IncidentSeverity.MEDIUM;
        String p = priority.trim().toUpperCase(Locale.ROOT);
        if (p.startsWith("P1") || p.startsWith("P2")) return IncidentSeverity.CRITICAL;
        if (p.startsWith("P3")) return IncidentSeverity.HIGH;
        if (p.startsWith("P4")) return IncidentSeverity.MEDIUM;
        if (p.startsWith("P5")) return IncidentSeverity.LOW;
        return IncidentSeverity.MEDIUM;
    }

    private IncidentSeverity victorOpsSeverity(String messageType) {
        return switch (messageType) {
            case "CRITICAL" -> IncidentSeverity.CRITICAL;
            case "WARNING" -> IncidentSeverity.HIGH;
            case "INFO" -> IncidentSeverity.LOW;
            default -> IncidentSeverity.MEDIUM;
        };
    }
}
