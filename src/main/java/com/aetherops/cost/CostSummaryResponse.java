package com.aetherops.cost;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class CostSummaryResponse {
    private UUID incidentId;
    private List<CostEntryResponse> entries;
    private long totalCents;
    private String currency;
}
