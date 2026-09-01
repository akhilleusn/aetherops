package com.aetherops.oncall;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deduplicated_alerts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeduplicatedAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "service", nullable = false, length = 100)
    private String service;

    @Column(name = "alert_name", nullable = false, length = 200)
    private String alertName;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private SeverityLevel severity;

    @Column(name = "incident_id")
    private UUID incidentId;

    @Column(name = "alert_count", nullable = false)
    @Builder.Default
    private int alertCount = 1;

    @CreationTimestamp
    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
