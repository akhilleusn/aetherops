package com.aetherops.monitoring.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Datadog's monitor webhook payload is user-templated (configured per-monitor in Datadog),
 * so this covers Datadog's documented default template fields — customers must map their
 * webhook payload to this shape when configuring the integration in Datadog.
 */
@Data
@NoArgsConstructor
public class DatadogWebhookRequest {

    @NotBlank(message = "serviceId is required")
    private String serviceId;

    @NotBlank(message = "title is required")
    private String title;

    private String text;

    /** Datadog monitor alert_type: error, warning, info, success, etc. */
    private String alertType;

    /** Datadog monitor alert_transition: Triggered, Recovered, Re-Triggered, etc. */
    private String alertTransition;

    /** Optional Datadog monitor priority: P1-P5. Overrides alertType-derived severity when present. */
    private String priority;

    private String environment;
}
