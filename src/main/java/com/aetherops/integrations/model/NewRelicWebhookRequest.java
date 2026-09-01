package com.aetherops.integrations.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** New Relic alerts/applied-intelligence workflow webhook payload (customer-configurable, this covers the documented default fields). */
@Data
@NoArgsConstructor
public class NewRelicWebhookRequest {

    private String issueId;
    private String title;

    /** CRITICAL, HIGH, MEDIUM, LOW. */
    private String priority;

    /** ACTIVATED or CLOSED. */
    private String state;

    private EntitiesData entitiesData;

    @Data
    @NoArgsConstructor
    public static class EntitiesData {
        private List<Entity> entities;
    }

    @Data
    @NoArgsConstructor
    public static class Entity {
        private String name;
    }
}
