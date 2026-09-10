package com.aetherops.integrations.model;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * VictorOps (Splunk On-Call) outbound webhook payload.
 *
 * <p>Phase 2 remediation, finding 3 (see STEP_REPORT.md): both {@code entity_id} and {@code
 * entity_display_name} can end up as {@code Incident.serviceId} (VARCHAR(100) NOT NULL,
 * IntegrationsWebhookController prefers entity_display_name, falls back to entity_id) — both
 * bounded here so an oversized value is rejected (400). {@code state_message} (used as title)
 * is NOT length-constrained here — truncated in {@code IntegrationsWebhookController} instead;
 * see that controller's comment.
 */
@Data
@NoArgsConstructor
public class VictorOpsWebhookRequest {

    /** CRITICAL, WARNING, INFO, or RECOVERY. */
    private String message_type;

    @Size(max = 100, message = "entity_id must not exceed 100 characters")
    private String entity_id;

    @Size(max = 100, message = "entity_display_name must not exceed 100 characters")
    private String entity_display_name;

    private String state_message;
}
