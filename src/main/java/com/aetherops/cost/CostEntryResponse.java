package com.aetherops.cost;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class CostEntryResponse {
    private UUID id;
    private UUID incidentId;
    private String description;
    private long amountCents;
    private String currency;
    private CostSource source;
    private String recordedBy;
    private Instant recordedAt;
}
