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
@Table(name = "incident_timeline")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentTimeline {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private TimelineEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 20)
    private SeverityLevel severity;

    /** Free-form context for the event, e.g. engineer name for ENGINEER_PAGED. */
    @Column(columnDefinition = "TEXT")
    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum TimelineEventType {
        CREATED,
        SEVERITY_CHANGED,
        ENGINEER_PAGED,
        /** Step G1 (see STEP_REPORT.md): every paging channel failed. ENGINEER_PAGED means the
         *  provider actually accepted a send; this is what gets recorded instead when none did. */
        PAGE_FAILED,
        RESOLVED
    }
}
