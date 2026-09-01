package com.aetherops.integrations.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class FlagEventResponse {
    private UUID id;
    private String flagKey;
    private String environment;
    private String changeDescription;
    private String changedBy;
    private Instant occurredAt;
}
