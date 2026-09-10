package com.aetherops.integrations.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * New Relic alerts/applied-intelligence workflow webhook payload (customer-configurable, this
 * covers the documented default fields).
 *
 * <p>Phase 2 remediation, finding 3 (see STEP_REPORT.md): {@code entitiesData.entities[].name}
 * is the source of {@code Incident.serviceId} (VARCHAR(100) NOT NULL, verified against
 * {@code V1__create_initial_schema.sql}) — bounded here with {@code @Size} so an oversized
 * value is cleanly rejected (400) rather than reaching an uncaught constraint violation.
 * {@code title} is deliberately NOT length-constrained here — it's truncated in
 * {@code IntegrationsWebhookController} instead, not rejected, since dropping an entire
 * third-party alert over an overlong title is worse than showing a shortened one. See that
 * controller's comment for the reasoning on why title and serviceId get different treatment.
 */
@Data
@NoArgsConstructor
public class NewRelicWebhookRequest {

    private String issueId;
    private String title;

    /** CRITICAL, HIGH, MEDIUM, LOW. */
    private String priority;

    /** ACTIVATED or CLOSED. */
    private String state;

    @Valid
    private EntitiesData entitiesData;

    @Data
    @NoArgsConstructor
    public static class EntitiesData {
        @Valid
        private List<Entity> entities;
    }

    @Data
    @NoArgsConstructor
    public static class Entity {
        @Size(max = 100, message = "entitiesData.entities[].name must not exceed 100 characters")
        private String name;
    }
}
