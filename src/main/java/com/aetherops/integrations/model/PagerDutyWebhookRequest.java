package com.aetherops.integrations.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/** PagerDuty webhook subscriptions v3 payload envelope. */
@Data
@NoArgsConstructor
public class PagerDutyWebhookRequest {

    private Event event;

    @Data
    @NoArgsConstructor
    public static class Event {
        /** e.g. "incident.triggered", "incident.acknowledged", "incident.resolved". */
        private String event_type;
        private EventData data;
    }

    @Data
    @NoArgsConstructor
    public static class EventData {
        private String id;
        private String title;
        private Priority priority;
        private ServiceRef service;
    }

    @Data
    @NoArgsConstructor
    public static class Priority {
        /** e.g. "P1" through "P5". */
        private String summary;
    }

    @Data
    @NoArgsConstructor
    public static class ServiceRef {
        private String summary;
    }
}
