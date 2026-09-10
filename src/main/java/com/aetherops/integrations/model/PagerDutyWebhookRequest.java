package com.aetherops.integrations.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PagerDuty webhook subscriptions v3 payload envelope.
 *
 * <p>Phase 2 remediation, finding 3 (see STEP_REPORT.md): {@code event.data.service.summary} is
 * the source of {@code Incident.serviceId} (VARCHAR(100) NOT NULL) — bounded here so an
 * oversized value is rejected (400). {@code event.data.title} is NOT length-constrained here —
 * truncated in {@code IntegrationsWebhookController} instead; see that controller's comment.
 */
@Data
@NoArgsConstructor
public class PagerDutyWebhookRequest {

    @Valid
    private Event event;

    @Data
    @NoArgsConstructor
    public static class Event {
        /** e.g. "incident.triggered", "incident.acknowledged", "incident.resolved". */
        private String event_type;
        @Valid
        private EventData data;
    }

    @Data
    @NoArgsConstructor
    public static class EventData {
        private String id;
        private String title;
        private Priority priority;
        @Valid
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
        @Size(max = 100, message = "event.data.service.summary must not exceed 100 characters")
        private String summary;
    }
}
