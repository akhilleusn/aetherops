package com.aetherops.integrations.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Rollbar notification webhook payload.
 *
 * <p>Phase 2 remediation, finding 3 (see STEP_REPORT.md): {@code data.item.project.name} is the
 * source of {@code Incident.serviceId} (VARCHAR(100) NOT NULL) — bounded here so an oversized
 * value is rejected (400). {@code data.item.title} is NOT length-constrained here — truncated
 * in {@code IntegrationsWebhookController} instead; see that controller's comment.
 */
@Data
@NoArgsConstructor
public class RollbarWebhookRequest {

    /** e.g. "new_item", "reactivated_item", "resolved_item", "exp_repeat_item". */
    private String event_name;

    @Valid
    private ItemData data;

    @Data
    @NoArgsConstructor
    public static class ItemData {
        @Valid
        private Item item;
    }

    @Data
    @NoArgsConstructor
    public static class Item {
        private String title;
        /** critical, error, warning, info, debug. */
        private String level;
        @Valid
        private Project project;
    }

    @Data
    @NoArgsConstructor
    public static class Project {
        @Size(max = 100, message = "data.item.project.name must not exceed 100 characters")
        private String name;
    }
}
