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

    /**
     * Phase 2 remediation, finding 14 (see STEP_REPORT.md): every per-table query here is raw
     * JDBC with an explicit {@code WHERE tenant_id = ?} bound to {@code tenantId} — but every
     * table also carries {@code FORCE ROW LEVEL SECURITY}, scoped to the CALLER's
     * {@code app.tenant_id} (propagated by {@code TenantContextInterceptor} from
     * {@code TenantContext.get()}). If {@code tenantId} here were ever allowed to differ from
     * the caller's own tenant, the explicit filter and the RLS filter would target two
     * different tenants and every query would silently return nothing — under-exporting rather
     * than throwing or leaking, which for a GDPR Art. 20 data-portability request means handing
     * back an export that looks complete but holds zero rows for the tenant that was actually
     * requested.
     *
     * <p>This is safe today ONLY because {@link com.aetherops.privacy.controller.PrivacyController}
     * is this method's sole caller, and it calls {@code TenantContext.requireOwn(tenantId)}
     * before invoking this method — which throws unless {@code tenantId} already equals the
     * caller's own tenant, i.e. the value {@code app.tenant_id} is about to be set to for this
     * same request. <b>If this method ever gains a second caller, that caller MUST enforce the
     * same constraint (or another guarantee that {@code tenantId} matches the active
     * {@code app.tenant_id}) before calling in — otherwise this silently under-exports instead
     * of returning the tenant's actual data.</b>
     */
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
