package com.aetherops.integrations.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sumo Logic lets customers template an arbitrary JSON body for a scheduled-search webhook
 * connection — this is the shape we ask tenants to template their webhook payload into,
 * same idea as com.aetherops.monitoring.model.DatadogWebhookRequest's documented default template.
 */
@Data
@NoArgsConstructor
public class SumoLogicWebhookRequest {

    @NotBlank(message = "serviceId is required")
    private String serviceId;

    @NotBlank(message = "alertName is required")
    private String alertName;

    private String description;

    /** CRITICAL, HIGH, MEDIUM, LOW. */
    private String severity;

    private boolean firing;
}
