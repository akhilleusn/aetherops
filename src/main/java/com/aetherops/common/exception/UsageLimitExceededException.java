package com.aetherops.common.exception;

import com.aetherops.tenant.model.Plan;
import com.aetherops.usage.model.UsageType;
import lombok.Getter;

import java.util.UUID;

@Getter
public class UsageLimitExceededException extends RuntimeException {

    private final UUID tenantId;
    private final UsageType usageType;
    private final String plan;
    private final int limit;

    public UsageLimitExceededException(UUID tenantId, UsageType type, Plan plan, int limit) {
        super(String.format(
            "Monthly %s limit of %d reached for tenant %s (plan: %s). Upgrade your plan to continue.",
            type, limit, tenantId, plan
        ));
        this.tenantId  = tenantId;
        this.usageType = type;
        this.plan      = plan.name();
        this.limit     = limit;
    }
}
