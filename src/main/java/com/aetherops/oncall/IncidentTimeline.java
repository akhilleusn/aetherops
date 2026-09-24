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
        RESOLVED,
        /** Recorded when an incident's status changes to CLOSED (recorded once; re-sending
         *  CLOSED for an already-closed incident adds nothing). */
        CLOSED,
        /** Step H1 Part 2 (see STEP_REPORT.md): a Slack workspace member clicked "Acknowledge"
         *  or "Resolve" on an incident message. Neither action executes anymore -- no
         *  Slack-user-to-AetherOps-user mapping exists to authorize either, so both redirect to
         *  the dashboard, where the real role check applies. This event is an audit trail of the
         *  attempt and the presented Slack identity in {@code detail}, recorded even though (in
         *  fact, especially because) no incident state changed. */
        SLACK_ACTION_REQUESTED
    }
}
