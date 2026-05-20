package com.aetherops.retention.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class RetentionResult {

    private UUID tenantId;
    private int retentionDays;
    private Instant cutoffDate;
    /** True if this was a dry run — no data was deleted. */
    private boolean dryRun;
    private long logEventsAffected;
    private long evidenceItemsAffected;
    private long incidentsAffected;
    private long analysesAffected;
    private long runbooksAffected;
    private long postmortemsAffected;
    private long totalAffected;
}
