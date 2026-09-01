package com.aetherops.integrations.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Opsgenie "outgoing webhook" integration payload. */
@Data
@NoArgsConstructor
public class OpsgenieWebhookRequest {

    /** "Create", "Close", "Acknowledge", etc. */
    private String action;

    private Alert alert;

    @Data
    @NoArgsConstructor
    public static class Alert {
        private String alertId;
        private String message;
        private String source;
        /** P1 (highest) through P5 (lowest). */
        private String priority;
        private List<String> tags;
    }
}
