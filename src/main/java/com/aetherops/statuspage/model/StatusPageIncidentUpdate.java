package com.aetherops.statuspage.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A curated, customer-facing update for one incident. Posting the first update for an
 * incident is what makes it appear on the public status page — internal incidents that
 * never get an update stay invisible to customers.
 */
@Entity
@Table(name = "status_page_incident_updates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusPageIncidentUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusPageUpdateStatus status;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    @CreationTimestamp
    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;
}
