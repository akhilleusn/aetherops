package com.aetherops.privacy.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.privacy.model.TenantDataExportResponse;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GDPR Art. 20 data portability: exports everything held for a tenant as plain JSON-able
 * structures. Table list is discovered dynamically (see {@link TenantScopedSchemaService})
 * so the export always covers the full current schema.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantDataExportService {

    private final TenantRepository tenantRepository;
    private final TenantScopedSchemaService schema;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public TenantDataExportResponse export(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        Map<String, Object> tenantRow = new LinkedHashMap<>();
        tenantRow.put("id", tenant.getId());
        tenantRow.put("name", tenant.getName());
        tenantRow.put("plan", tenant.getPlan());
        tenantRow.put("createdAt", tenant.getCreatedAt());

        List<String> tables = schema.tenantScopedTables();
        Map<String, List<Map<String, Object>>> tableData = new LinkedHashMap<>();
        long totalRows = 0;
        for (String table : tables) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM " + schema.quoteIdentifier(table) + " WHERE tenant_id = ?", tenantId);
            tableData.put(table, rows);
            totalRows += rows.size();
        }

        log.info("GDPR export: tenant={} tables={} totalRows={}", tenantId, tables.size(), totalRows);

        return TenantDataExportResponse.builder()
            .tenantId(tenantId)
            .exportedAt(Instant.now())
            .tenant(tenantRow)
            .tables(tableData)
            .build();
    }
}
