package com.aetherops.warroom;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "war_rooms")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WarRoomStatus status;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    /** "AUTO" (opened by {@link WarRoomAutoOpenJob}) or "MANUAL" (opened via the API). */
    @Column(name = "opened_by", nullable = false, length = 20)
    private String openedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Snapshotted from the incident at open time — the incident's own Slack fields may change later. */
    @Column(name = "slack_channel_id", length = 50)
    private String slackChannelId;

    @Column(name = "slack_thread_ts", length = 50)
    private String slackThreadTs;
}
