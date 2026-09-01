package com.aetherops.escalation;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class EscalationExecutionResponse {
    private UUID id;
    private UUID incidentId;
    private int escalationLevel;
    private Instant escalatedAt;
}
