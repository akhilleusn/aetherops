package com.aetherops.privacy.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full data-portability export for a tenant (GDPR Art. 20). {@code tables} holds every
 * tenant-scoped table's rows keyed by table name, discovered dynamically at export time
 * so newly added tables are included without code changes.
 */
@Data
@Builder
public class TenantDataExportResponse {

    private UUID tenantId;
    private Instant exportedAt;
    private Map<String, Object> tenant;
    private Map<String, List<Map<String, Object>>> tables;
}
