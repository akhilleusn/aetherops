package com.aetherops.privacy.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.privacy.model.TenantErasureResult;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;

/**
 * GDPR Art. 17 right to erasure: deletes every row held for a tenant, then the tenant
 * itself. Table list comes from {@link TenantScopedSchemaService}, and deletion order is
 * a topological sort over the schema's actual foreign keys (children before parents) so
 * this never needs updating by hand when a new tenant-scoped table is added.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantErasureService {

    private final TenantRepository tenantRepository;
    private final TenantScopedSchemaService schema;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Phase 2 remediation, finding 14 (see STEP_REPORT.md): every query in this method is raw
     * JDBC with an explicit {@code WHERE tenant_id = ?} bound to {@code tenantId} — but every
     * table involved also carries {@code FORCE ROW LEVEL SECURITY}, scoped to the CALLER's
     * {@code app.tenant_id} (propagated by {@code TenantContextInterceptor} from
     * {@code TenantContext.get()}). If {@code tenantId} here were ever allowed to differ from
     * the caller's own tenant, the explicit filter and the RLS filter would target two
     * different tenants and every query would silently match nothing — under-deleting rather
     * than throwing or leaking, which is far worse for a GDPR erasure: it would report success
     * while deleting zero rows for the tenant that was actually requested.
     *
     * <p>This is safe today ONLY because {@link com.aetherops.privacy.controller.PrivacyController}
     * is this method's sole caller, and it calls {@code TenantContext.requireOwn(tenantId)}
     * before invoking this method — which throws unless {@code tenantId} already equals the
     * caller's own tenant, i.e. the value {@code app.tenant_id} is about to be set to for this
     * same request. <b>If this method ever gains a second caller, that caller MUST enforce the
     * same constraint (or another guarantee that {@code tenantId} matches the active
     * {@code app.tenant_id}) before calling in — otherwise this silently under-deletes instead
     * of erasing the tenant that was actually asked for.</b>
     */
    @Transactional
    public TenantErasureResult erase(UUID tenantId, boolean dryRun) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new ResourceNotFoundException("Tenant not found: " + tenantId);
        }

        List<String> tables = deletionOrder(schema.tenantScopedTables(), schema.foreignKeyEdges());

        if (dryRun) {
            Map<String, Long> counts = new LinkedHashMap<>();
            long total = 0;
            for (String table : tables) {
                Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema.quoteIdentifier(table) + " WHERE tenant_id = ?",
                    Long.class, tenantId);
                counts.put(table, count);
                total += count;
            }
            log.info("GDPR erasure dry-run: tenant={} eligibleRows={}", tenantId, total);
            return TenantErasureResult.builder()
                .tenantId(tenantId).dryRun(true).tenantDeleted(false)
                .rowsAffectedByTable(counts).totalRowsAffected(total)
                .build();
        }

        Map<String, Long> deleted = new LinkedHashMap<>();
        long total = 0;
        for (String table : tables) {
            int rows = jdbcTemplate.update(
                "DELETE FROM " + schema.quoteIdentifier(table) + " WHERE tenant_id = ?", tenantId);
            deleted.put(table, (long) rows);
            total += rows;
        }

        tenantRepository.deleteById(tenantId);

        log.info("GDPR erasure completed: tenant={} rowsDeleted={} tables={}", tenantId, total, tables.size());

        return TenantErasureResult.builder()
            .tenantId(tenantId).dryRun(false).tenantDeleted(true)
            .rowsAffectedByTable(deleted).totalRowsAffected(total)
            .build();
    }

    /**
     * Topologically sorts {@code tables} so that for every foreign key edge (child, parent)
     * where both ends are in {@code tables}, the child comes before the parent. Tables
     * with no relevant edges keep their original (alphabetical) relative order.
     */
    private List<String> deletionOrder(List<String> tables, List<String[]> edges) {
        Set<String> nodes = new LinkedHashSet<>(tables);
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String t : nodes) inDegree.put(t, 0);

        for (String[] edge : edges) {
            String child = edge[0];
            String parent = edge[1];
            if (!nodes.contains(child) || !nodes.contains(parent) || child.equals(parent)) continue;
            dependents.computeIfAbsent(child, k -> new ArrayList<>()).add(parent);
            inDegree.merge(parent, 1, Integer::sum);
        }

        Deque<String> ready = new ArrayDeque<>();
        for (String t : tables) {
            if (inDegree.get(t) == 0) ready.addLast(t);
        }

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String t = ready.pollFirst();
            order.add(t);
            for (String dependent : dependents.getOrDefault(t, List.of())) {
                int remaining = inDegree.merge(dependent, -1, Integer::sum);
                if (remaining == 0) ready.addLast(dependent);
            }
        }

        // A cycle would mean two tenant-scoped tables have mutually referencing FKs, which
        // this schema doesn't have; fall back to the discovery order for anything left over
        // rather than silently dropping tables from the erasure.
        if (order.size() != tables.size()) {
            for (String t : tables) {
                if (!order.contains(t)) order.add(t);
            }
        }
        return order;
    }
}
