package com.aetherops.sla;

import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Sweeps every tenant looking for newly-crossed SLA deadlines. Runs one tenant at a
 * time, setting TenantContext before each check — the incidents table enforces
 * row-level security keyed on that context (see V28__enable_row_level_security.sql),
 * so a single cross-tenant query would silently return nothing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlaMonitoringJob {

    private final TenantRepository tenantRepository;
    private final SlaTrackingService slaTrackingService;

    @Scheduled(fixedDelay = 300_000)
    public void checkAllTenants() {
        List<UUID> tenantIds = tenantRepository.findAll().stream().map(Tenant::getId).toList();
        log.debug("Running SLA breach sweep for {} tenants", tenantIds.size());

        for (UUID tenantId : tenantIds) {
            try {
                TenantContext.set(tenantId);
                slaTrackingService.checkAndMarkBreaches(tenantId);
            } catch (Exception e) {
                log.error("SLA breach sweep failed for tenantId={}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
