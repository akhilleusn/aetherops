package com.aetherops.escalation;

import com.aetherops.incident.model.IncidentSeverity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "escalation_policies")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EscalationPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Null represents the tenant-wide default policy. */
    @Column(name = "service_id", length = 100)
    private String serviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity_threshold", nullable = false, length = 20)
    private IncidentSeverity severityThreshold;

    @Column(name = "escalate_after_minutes", nullable = false)
    private int escalateAfterMinutes;

    @Column(nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
