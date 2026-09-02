package com.aetherops.tenant;

import com.aetherops.common.exception.ForbiddenException;

import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static UUID get() {
        return TENANT_ID.get();
    }

    public static void clear() {
        TENANT_ID.remove();
    }

    /**
     * Throws if {@code tenantId} (a caller-supplied path/query/body value) isn't the
     * caller's own tenant, per their JWT. Most controllers take a tenantId as plain
     * input with no such check, relying solely on PostgreSQL RLS as a backstop — but RLS
     * doesn't cover every table, so this is the enforcement point for endpoints where
     * that matters. Call it before touching any tenant-scoped data.
     */
    public static void requireOwn(UUID tenantId) {
        UUID callerTenantId = get();
        if (callerTenantId == null || !callerTenantId.equals(tenantId)) {
            throw new ForbiddenException("Cannot access another tenant's data");
        }
    }
}
