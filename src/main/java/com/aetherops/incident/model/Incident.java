package com.aetherops.incident.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incidents")
@SQLRestriction("deleted_at IS NULL")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private IncidentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentSeverity severity;

    @Column(name = "service_id", nullable = false, length = 100)
    private String serviceId;

    @Column(length = 50)
    private String environment;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "latest_event_id")
    private UUID latestEventId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public void resolve() {
        if (this.status == IncidentStatus.RESOLVED) {
            throw new IllegalStateException("Incident " + id + " is already resolved");
        }
        this.status = IncidentStatus.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    public void escalate(IncidentSeverity newSeverity) {
        if (newSeverity.ordinal() >= this.severity.ordinal()) {
            throw new IllegalStateException(
                    "Cannot escalate incident " + id + " from " + severity + " to " + newSeverity
                            + ": target severity must be higher");
        }
        this.severity = newSeverity;
    }

    public void reopen() {
        if (this.status != IncidentStatus.RESOLVED && this.status != IncidentStatus.CLOSED) {
            throw new IllegalStateException(
                    "Cannot reopen incident " + id + " with status " + status
                            + ": only RESOLVED or CLOSED incidents can be reopened");
        }
        this.status = IncidentStatus.OPEN;
        this.resolvedAt = null;
    }
}