package com.aetherops.usage.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class UsageSummaryResponse {

    private UUID tenantId;
    private String plan;
    /** Calendar month in YYYY-MM format. */
    private String period;
    /** Keyed by UsageType; serialised as the enum name string. */
    private Map<UsageType, UsageEntry> usage;

    @Data
    @Builder
    public static class UsageEntry {
        private int used;
        /** Monthly limit for this plan and type. -1 means unlimited. */
        private int limit;
    }
}
