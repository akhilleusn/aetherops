package com.aetherops.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void setAndGet_returnsSameValue() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        assertThat(TenantContext.get()).isEqualTo(tenantId);
    }

    @Test
    void clear_removesValue() {
        TenantContext.set(UUID.randomUUID());
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void getBeforeSet_returnsNull() {
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void set_overwritesPreviousValue() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        TenantContext.set(first);
        TenantContext.set(second);
        assertThat(TenantContext.get()).isEqualTo(second);
    }
}
