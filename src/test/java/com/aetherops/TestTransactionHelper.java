package com.aetherops;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Test-only bean whose {@code @Transactional} boundary is what actually triggers
 * TenantContextInterceptor's propagation of app.tenant_id to Postgres. A bare repository
 * call from test code (e.g. {@code incidentRepository.findAll()}) never goes through that
 * aspect on its own — Spring Data's own transactional advisor for repository proxies is a
 * separate mechanism the aspect doesn't see — so under RLS it silently returns zero rows
 * even with TenantContext.set() called first. Call TenantContext.set(tenantId) BEFORE
 * invoking {@link #runInTransaction}, since the aspect's @Before advice reads it at method
 * entry, before this method's body runs.
 */
@Component
public class TestTransactionHelper {

    @Transactional
    public <T> T runInTransaction(Supplier<T> action) {
        return action.get();
    }
}
