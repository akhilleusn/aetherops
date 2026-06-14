package com.aetherops.incident.event;

import java.time.Instant;
import java.util.UUID;

public record IncidentCreatedDomainEvent(
        UUID incidentId,
        UUID tenantId,
        String severity,
        String title,
        Instant occurredAt
) {
    public static IncidentCreatedDomainEvent of(UUID incidentId, UUID tenantId, String severity,
                                                 String title, Instant occurredAt) {
        return new IncidentCreatedDomainEvent(incidentId, tenantId, severity, title, occurredAt);
    }
}
