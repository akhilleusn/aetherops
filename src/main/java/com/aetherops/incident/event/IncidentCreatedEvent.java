package com.aetherops.incident.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentCreatedEvent {

    private UUID incidentId;
    private UUID tenantId;
    private String severity;
    private String title;
    private Instant occurredAt;
}