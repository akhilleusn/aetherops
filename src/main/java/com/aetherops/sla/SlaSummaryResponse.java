package com.aetherops.sla;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class SlaSummaryResponse {
    private UUID tenantId;
    private String period;
    private int totalIncidents;
    private int responseMet;
    private int responseBreached;
    private int resolutionMet;
    private int resolutionBreached;
    /** Null when no incidents in the period have been acknowledged/resolved yet. */
    private Double avgResponseMinutes;
    private Double avgResolutionMinutes;
}
