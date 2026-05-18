package com.aetherops.evidence.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evidence_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "log_event_id", nullable = false)
    private UUID logEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EvidenceType type;

    @Column(name = "source_ref", nullable = false, length = 255)
    private String sourceRef;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(length = 20)
    private String level;

    @Column(nullable = false)
    private Instant timestamp;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
