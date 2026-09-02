package com.aetherops.privacy.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * Result of a GDPR right-to-erasure run (Art. 17). {@code dryRun=true} only counts rows
 * eligible for deletion; {@code dryRun=false} deletes them, then the tenant itself.
 */
@Data
@Builder
public class TenantErasureResult {

    private UUID tenantId;
    private boolean dryRun;
    private boolean tenantDeleted;
    private Map<String, Long> rowsAffectedByTable;
    private long totalRowsAffected;
}
