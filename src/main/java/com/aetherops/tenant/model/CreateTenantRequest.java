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

    @NotNull(message = "plan is required")
    private Plan plan;

    @NotNull(message = "aiMonthlyBudgetCents is required")
    @Min(value = 0, message = "aiMonthlyBudgetCents must be >= 0")
    private Integer aiMonthlyBudgetCents;

    @NotNull(message = "retentionDays is required")
    @Min(value = 1, message = "retentionDays must be >= 1")
    @Max(value = 365, message = "retentionDays must be <= 365")
    private Integer retentionDays;
}
