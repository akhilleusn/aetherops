package com.aetherops.incident.event;

import java.time.Instant;
import java.util.UUID;

public record IncidentResolvedDomainEvent(
        UUID incidentId,
        UUID tenantId,
        Instant resolvedAt
) {
    public static IncidentResolvedDomainEvent of(UUID incidentId, UUID tenantId, Instant resolvedAt) {
        return new IncidentResolvedDomainEvent(incidentId, tenantId, resolvedAt);
    }
}
