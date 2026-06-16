package com.aetherops.tenant;

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
}
