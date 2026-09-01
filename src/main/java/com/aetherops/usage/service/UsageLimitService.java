package com.aetherops.usage.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.common.exception.UsageLimitExceededException;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.aetherops.usage.model.*;
import com.aetherops.usage.repository.TenantMonthlyUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UsageLimitService {

    private final TenantRepository tenantRepository;
    private final TenantMonthlyUsageRepository usageRepository;

    /**
     * Checks the monthly limit for the given usage type and atomically increments the counter
     * if the limit has not been reached.
     *
     * Must be called as the FIRST statement in a {@code @Transactional} service method so that:
     * - A rejected call (429) makes no side effects — the increment never happens.
     * - A failed generation rolls back the increment along with any other writes in the
     *   same transaction, satisfying the requirement that failed calls are not metered.
     *
     * Unregistered tenants (no row in the tenants table) are not metered; the call is allowed
     * without recording usage. This preserves existing integration tests that use hardcoded
     * tenant UUIDs without creating tenant rows.
     */
    @Transactional
    public void checkAndIncrement(UUID tenantId, UsageType type) {
        Optional<Tenant> tenantOpt = tenantRepository.findById(tenantId);
        if (tenantOpt.isEmpty()) {
            return;
        }

        Tenant tenant = tenantOpt.get();
        int limit  = PlanLimits.limitFor(tenant.getPlan(), type);
        String period = YearMonth.now().toString();

        TenantMonthlyUsage usage = usageRepository
            .findByTenantIdAndPeriod(tenantId, period)
            .orElseGet(() -> TenantMonthlyUsage.builder()
                .tenantId(tenantId)
                .period(period)
                .aiAnalysisCount(0)
                .runbookGenerationCount(0)
                .postmortemGenerationCount(0)
                .incidentCount(0)
                .build());

        if (!PlanLimits.isUnlimited(limit) && currentCount(usage, type) >= limit) {
            throw new UsageLimitExceededException(tenantId, type, tenant.getPlan(), limit);
        }

        increment(usage, type);
        usageRepository.save(usage);

        log.debug("Usage increment: tenant={} type={} period={} count={}",
            tenantId, type, period, currentCount(usage, type));
    }

    /**
     * Returns the current-month usage summary for the tenant.
     * Throws {@link ResourceNotFoundException} if the tenant does not exist.
     */
    @Transactional(readOnly = true)
    public UsageSummaryResponse getSummary(UUID tenantId, String periodParam) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        String period = (periodParam != null && !periodParam.isBlank())
            ? periodParam
            : YearMonth.now().toString();

        Optional<TenantMonthlyUsage> usageOpt = usageRepository.findByTenantIdAndPeriod(tenantId, period);

        int aiCount       = usageOpt.map(TenantMonthlyUsage::getAiAnalysisCount).orElse(0);
        int runbookCount  = usageOpt.map(TenantMonthlyUsage::getRunbookGenerationCount).orElse(0);
        int postCount     = usageOpt.map(TenantMonthlyUsage::getPostmortemGenerationCount).orElse(0);
        int incidentCount = usageOpt.map(TenantMonthlyUsage::getIncidentCount).orElse(0);
        Plan plan         = tenant.getPlan();

        return UsageSummaryResponse.builder()
            .tenantId(tenantId)
            .plan(plan.name())
            .period(period)
            .usage(Map.of(
                UsageType.AI_ANALYSIS,
                    entry(aiCount,      plan, UsageType.AI_ANALYSIS),
                UsageType.RUNBOOK_GENERATION,
                    entry(runbookCount, plan, UsageType.RUNBOOK_GENERATION),
                UsageType.POSTMORTEM_GENERATION,
                    entry(postCount,    plan, UsageType.POSTMORTEM_GENERATION),
                UsageType.INCIDENT_CREATION,
                    entry(incidentCount, plan, UsageType.INCIDENT_CREATION)
            ))
            .build();
    }

    private int currentCount(TenantMonthlyUsage usage, UsageType type) {
        return switch (type) {
            case AI_ANALYSIS          -> usage.getAiAnalysisCount();
            case RUNBOOK_GENERATION   -> usage.getRunbookGenerationCount();
            case POSTMORTEM_GENERATION -> usage.getPostmortemGenerationCount();
            case INCIDENT_CREATION    -> usage.getIncidentCount();
        };
    }

    private void increment(TenantMonthlyUsage usage, UsageType type) {
        switch (type) {
            case AI_ANALYSIS           -> usage.setAiAnalysisCount(usage.getAiAnalysisCount() + 1);
            case RUNBOOK_GENERATION    -> usage.setRunbookGenerationCount(usage.getRunbookGenerationCount() + 1);
            case POSTMORTEM_GENERATION -> usage.setPostmortemGenerationCount(usage.getPostmortemGenerationCount() + 1);
            case INCIDENT_CREATION     -> usage.setIncidentCount(usage.getIncidentCount() + 1);
        }
    }

    private UsageSummaryResponse.UsageEntry entry(int used, Plan plan, UsageType type) {
        int raw = PlanLimits.limitFor(plan, type);
        return UsageSummaryResponse.UsageEntry.builder()
            .used(used)
            .limit(PlanLimits.toDisplayLimit(raw))
            .build();
    }
}
