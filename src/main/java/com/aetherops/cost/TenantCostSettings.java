package com.aetherops.cost;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_cost_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantCostSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Column(name = "hourly_responder_rate_cents", nullable = false)
    @Builder.Default
    private long hourlyResponderRateCents = IncidentCostService.DEFAULT_HOURLY_RATE_CENTS;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
