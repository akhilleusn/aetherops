package com.aetherops.integrations.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Opsgenie "outgoing webhook" integration payload.
 *
 * <p>Phase 2 remediation, finding 3 (see STEP_REPORT.md): {@code alert.source} is the source of
 * {@code Incident.serviceId} (VARCHAR(100) NOT NULL) — bounded here so an oversized value is
 * rejected (400). {@code alert.message} (used as title) is NOT length-constrained here —
 * truncated in {@code IntegrationsWebhookController} instead; see that controller's comment.
 */
@Data
@NoArgsConstructor
public class OpsgenieWebhookRequest {

    /** "Create", "Close", "Acknowledge", etc. */
    private String action;

    @Valid
    private Alert alert;

    @Data
    @NoArgsConstructor
    public static class Alert {
        private String alertId;
        private String message;
        @Size(max = 100, message = "alert.source must not exceed 100 characters")
        private String source;
        /** P1 (highest) through P5 (lowest). */
        private String priority;
        private List<String> tags;
    }
}
