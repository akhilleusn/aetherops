package com.aetherops.integrations.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Rollbar notification webhook payload. */
@Data
@NoArgsConstructor
public class RollbarWebhookRequest {

    /** e.g. "new_item", "reactivated_item", "resolved_item", "exp_repeat_item". */
    private String event_name;

    private ItemData data;

    @Data
    @NoArgsConstructor
    public static class ItemData {
        private Item item;
    }

    @Data
    @NoArgsConstructor
    public static class Item {
        private String title;
        /** critical, error, warning, info, debug. */
        private String level;
        private Project project;
    }

    @Data
    @NoArgsConstructor
    public static class Project {
        private String name;
    }
}
