package com.aetherops.analytics;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class AnalyticsDashboardResponse {

    private UUID tenantId;
    private Instant periodStart;
    private Instant periodEnd;
    private int totalIncidents;
    private int resolvedCount;
    /** Mean time to resolve, in minutes. Null when no incidents in range were resolved. */
    private Double mttrMinutes;
    /** Mean time between incident starts, in minutes. Null when fewer than 2 incidents. */
    private Double mtbfMinutes;
    private List<DailyCount> trend;
    private List<TeamPerformance> teamPerformance;

    @Data
    @Builder
    public static class DailyCount {
        private String date;
        private int count;
    }

    @Data
    @Builder
    public static class TeamPerformance {
        private String team;
        private int incidentCount;
        private Double mttrMinutes;
    }
}
