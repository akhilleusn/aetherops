package com.aetherops.tenant;

import com.aetherops.common.exception.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void requireOwn_passesWhenTenantMatchesCaller() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        assertThatCode(() -> TenantContext.requireOwn(tenantId)).doesNotThrowAnyException();
    }

    @Test
    void requireOwn_throwsWhenTenantDoesNotMatchCaller() {
        TenantContext.set(UUID.randomUUID());

        assertThatThrownBy(() -> TenantContext.requireOwn(UUID.randomUUID()))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void requireOwn_throwsWhenNoCallerContext() {
        assertThatThrownBy(() -> TenantContext.requireOwn(UUID.randomUUID()))
            .isInstanceOf(ForbiddenException.class);
    }
}
