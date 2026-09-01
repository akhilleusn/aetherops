package com.aetherops.escalation;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "escalation_executions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EscalationExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @CreationTimestamp
    @Column(name = "escalated_at", nullable = false, updatable = false)
    private Instant escalatedAt;
}
