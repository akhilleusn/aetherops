package com.aetherops.integrations.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Grafana unified alerting's webhook contact point payload. Alertmanager-compatible shape
 * (Grafana's alerting engine is built on the same model), so this mirrors
 * com.aetherops.monitoring.model.AlertmanagerWebhookRequest's handling but is kept as its
 * own DTO — this package doesn't depend on the monitoring package's internal types.
 */
@Data
@NoArgsConstructor
public class GrafanaWebhookRequest {

    /** Overall webhook status: "firing" or "resolved". Per-alert status in alerts[] takes priority when present. */
    private String status;

    private List<Alert> alerts;

    @Data
    @NoArgsConstructor
    public static class Alert {
        private String status;
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private Instant startsAt;
    }
}
