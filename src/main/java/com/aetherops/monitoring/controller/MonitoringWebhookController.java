package com.aetherops.monitoring.controller;

import com.aetherops.apikey.model.ApiKey;
import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.monitoring.model.AlertmanagerWebhookRequest;
import com.aetherops.monitoring.model.DatadogWebhookRequest;
import com.aetherops.monitoring.service.MonitoringAlertIngestionService;
import com.aetherops.monitoring.service.MonitoringAlertReplayGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/monitoring")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Monitoring Webhooks", description = "Auto-create incidents from Datadog and Prometheus Alertmanager")
public class MonitoringWebhookController {

    private final ApiKeyService apiKeyService;
    private final MonitoringAlertIngestionService ingestionService;
    private final MonitoringAlertReplayGuard replayGuard;

    /** Incident.title VARCHAR(500) — see V1__create_initial_schema.sql. */
    private static final int TITLE_MAX = 500;
    /** Incident.service_id VARCHAR(100) — see V1__create_initial_schema.sql. */
    private static final int SERVICE_ID_MAX = 100;
    /** Incident.environment VARCHAR(50) — see V1__create_initial_schema.sql. */
    private static final int ENVIRONMENT_MAX = 50;

    /**
     * Phase 2 remediation, finding 3 (see STEP_REPORT.md): Alertmanager's labels/annotations are
     * Map&lt;String,String&gt; — Bean Validation cannot constrain map values — so this truncates
     * rather than rejects, matching IntegrationsWebhookController#truncate's Grafana handling of
     * the identical payload shape.
     */
    private String truncate(String value, int maxLen) {
        return value != null && value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    @PostMapping("/datadog")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Datadog monitor webhook — configure the monitor's webhook payload to match this shape")
    public ResponseEntity<Void> datadog(
        @Valid @RequestBody DatadogWebhookRequest request,
        @RequestHeader("X-Api-Key") String apiKeyHeader
    ) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);
        boolean firing = !"Recovered".equalsIgnoreCase(request.getAlertTransition());

        if (replayGuard.isReplay(apiKey.getTenantId(), "datadog",
                ingestionService.computeFingerprint(apiKey.getTenantId(), request.getServiceId(), request.getTitle()))) {
            return ResponseEntity.accepted().build();
        }

        ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
            apiKey.getTenantId(), apiKey.getProjectId(), request.getServiceId(), request.getTitle(),
            request.getTitle(), request.getText(), datadogSeverity(request), firing,
            request.getEnvironment(), Instant.now()));

        return ResponseEntity.accepted().build();
    }

    @PostMapping("/prometheus/alertmanager")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Prometheus Alertmanager webhook_config receiver")
    public ResponseEntity<Void> prometheusAlertmanager(
        @Valid @RequestBody AlertmanagerWebhookRequest request,
        @RequestHeader("X-Api-Key") String apiKeyHeader
    ) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);

        List<AlertmanagerWebhookRequest.Alert> alerts = request.getAlerts() != null ? request.getAlerts() : List.of();
        for (AlertmanagerWebhookRequest.Alert alert : alerts) {
            Map<String, String> labels = alert.getLabels() != null ? alert.getLabels() : Map.of();
            Map<String, String> annotations = alert.getAnnotations() != null ? alert.getAnnotations() : Map.of();

            String alertName = labels.getOrDefault("alertname", "unknown_alert");
            String serviceId = truncate(labels.getOrDefault("service", labels.getOrDefault("job", "unknown-service")), SERVICE_ID_MAX);
            String summary = truncate(annotations.getOrDefault("summary", alertName), TITLE_MAX);
            String description = annotations.getOrDefault("description", summary);
            String environment = truncate(labels.get("environment"), ENVIRONMENT_MAX);
            boolean firing = "firing".equalsIgnoreCase(alert.getStatus());

            if (replayGuard.isReplay(apiKey.getTenantId(), "alertmanager",
                    ingestionService.computeFingerprint(apiKey.getTenantId(), serviceId, alertName))) {
                continue;
            }

            ingestionService.ingest(new MonitoringAlertIngestionService.AlertSignal(
                apiKey.getTenantId(), apiKey.getProjectId(), serviceId, alertName,
                summary, description, alertmanagerSeverity(labels.get("severity")), firing,
                environment, alert.getStartsAt() != null ? alert.getStartsAt() : Instant.now()));
        }

        return ResponseEntity.accepted().build();
    }

    private IncidentSeverity datadogSeverity(DatadogWebhookRequest request) {
        if (request.getPriority() != null) {
            String p = request.getPriority().trim().toUpperCase(Locale.ROOT);
            if (p.startsWith("P1") || p.startsWith("P2")) return IncidentSeverity.CRITICAL;
            if (p.startsWith("P3")) return IncidentSeverity.HIGH;
            if (p.startsWith("P4")) return IncidentSeverity.MEDIUM;
            if (p.startsWith("P5")) return IncidentSeverity.LOW;
        }
        String alertType = request.getAlertType() != null ? request.getAlertType().toLowerCase(Locale.ROOT) : "";
        return switch (alertType) {
            case "error" -> IncidentSeverity.HIGH;
            case "warning" -> IncidentSeverity.MEDIUM;
            default -> IncidentSeverity.LOW;
        };
    }

    private IncidentSeverity alertmanagerSeverity(String severityLabel) {
        if (severityLabel == null) return IncidentSeverity.MEDIUM;
        return switch (severityLabel.toLowerCase(Locale.ROOT)) {
            case "critical", "page" -> IncidentSeverity.CRITICAL;
            case "warning" -> IncidentSeverity.HIGH;
            case "info" -> IncidentSeverity.LOW;
            default -> IncidentSeverity.MEDIUM;
        };
    }
}
