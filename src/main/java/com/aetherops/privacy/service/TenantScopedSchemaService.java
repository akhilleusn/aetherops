package com.aetherops.privacy.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Discovers, at run time, every table that carries a {@code tenant_id} column. GDPR export
 * and erasure both walk this list so a newly added tenant-scoped table (a new feature's
 * migration) is covered automatically, without a hand-maintained table list going stale.
 */
@Component
@RequiredArgsConstructor
public class TenantScopedSchemaService {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final JdbcTemplate jdbcTemplate;

    /** Every public-schema table with a {@code tenant_id} column, excluding {@code tenants} itself. */
    public List<String> tenantScopedTables() {
        return jdbcTemplate.queryForList(
            "SELECT DISTINCT table_name FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND column_name = 'tenant_id' AND table_name <> 'tenants' " +
                "ORDER BY table_name",
            String.class
        );
    }

    /**
     * (child table -> parent table) for every foreign key in the schema. Used to delete
     * child rows before parent rows so erasure never trips a foreign-key violation,
     * regardless of which tables a future migration adds.
     */
    public List<String[]> foreignKeyEdges() {
        return jdbcTemplate.query(
            "SELECT conrelid::regclass::text AS child_table, confrelid::regclass::text AS parent_table " +
                "FROM pg_constraint WHERE contype = 'f'",
            (rs, rowNum) -> new String[] { rs.getString("child_table"), rs.getString("parent_table") }
        );
    }

    /**
     * Table names here always come from {@code information_schema}/{@code pg_constraint},
     * never from request input — this check is defense in depth before the name is
     * concatenated into a query, not a guard against untrusted input.
     */
    public String quoteIdentifier(String tableName) {
        if (!SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalStateException("Refusing to build a query against unexpected table name: " + tableName);
        }
        return "\"" + tableName + "\"";
    }
}
