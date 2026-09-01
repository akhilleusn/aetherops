package com.aetherops.cost;

import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantCostSettingsRequest {

    @Positive(message = "hourlyResponderRateCents must be positive")
    private long hourlyResponderRateCents;
}
