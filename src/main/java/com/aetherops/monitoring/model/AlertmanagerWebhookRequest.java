package com.aetherops.monitoring.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Matches Prometheus Alertmanager's standard webhook_receiver JSON schema. */
@Data
@NoArgsConstructor
public class AlertmanagerWebhookRequest {

    private String version;
    private String groupKey;
    /** "firing" or "resolved" for the group as a whole. */
    private String status;
    private String receiver;
    private Map<String, String> groupLabels;
    private Map<String, String> commonLabels;
    private Map<String, String> commonAnnotations;
    private String externalURL;
    private List<Alert> alerts;

    @Data
    @NoArgsConstructor
    public static class Alert {
        /** "firing" or "resolved" for this individual alert. */
        private String status;
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private Instant startsAt;
        private Instant endsAt;
        private String generatorURL;
        private String fingerprint;
    }
}
