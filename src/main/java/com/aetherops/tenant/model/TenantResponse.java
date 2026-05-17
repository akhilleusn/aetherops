package com.aetherops.tenant.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class TenantResponse {

    private UUID id;
    private String name;
    private Plan plan;
    private Integer aiMonthlyBudgetCents;
    private Integer retentionDays;
    private Instant createdAt;
    private Instant updatedAt;
}
