package com.aetherops.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class TenantContextInterceptor {

    @PersistenceContext
    private EntityManager entityManager;

    @Before("@annotation(org.springframework.transaction.annotation.Transactional)")
    public void propagateTenantContext() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) return;
        try {
            entityManager.createNativeQuery(
                    "SELECT set_config('app.tenant_id', :tid, true)"
            ).setParameter("tid", tenantId.toString())
             .getSingleResult();
            log.debug("Propagated app.tenant_id={} to PostgreSQL RLS context", tenantId);
        } catch (Exception e) {
            log.warn("Could not propagate tenant context to database session: {}", e.getMessage());
        }
    }
}
