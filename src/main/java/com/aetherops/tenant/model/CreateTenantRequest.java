package com.aetherops.tenant.model;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateTenantRequest {

    @NotBlank(message = "name is required")
    @Size(min = 2, max = 100, message = "name must be between 2 and 100 characters")
    private String name;

    // Phase 2 remediation, finding 7 (see STEP_REPORT.md): plan and aiMonthlyBudgetCents were
    // removed from this request -- a client could declare its own billing plan (PlanLimits
    // grants GROWTH tenants unlimited AI-analysis calls against real Anthropic API spend) and
    // an unbounded aiMonthlyBudgetCents (which turned out to be write-only -- never read or
    // enforced anywhere). Every new tenant starts on Plan.FREE; upgrades happen exclusively
    // through SubscriptionService's real Stripe checkout flow, which was already the only
    // legitimate path and is untouched by this change.

    @NotNull(message = "retentionDays is required")
    @Min(value = 1, message = "retentionDays must be >= 1")
    @Max(value = 365, message = "retentionDays must be <= 365")
    private Integer retentionDays;
}
