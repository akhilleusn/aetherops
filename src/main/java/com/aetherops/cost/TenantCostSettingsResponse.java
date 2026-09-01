package com.aetherops.cost;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class TenantCostSettingsResponse {
    private UUID tenantId;
    private long hourlyResponderRateCents;
    private Instant updatedAt;
}
