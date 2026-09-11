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

    /**
     * Step G1 (see STEP_REPORT.md): per-channel delivery outcome for one escalation step.
     * {@code NOT_ATTEMPTED} means this channel was never tried -- either the step's fallback
     * chain stopped at an earlier channel that already succeeded, or (e.g. Slack) there was
     * nothing configured to attempt it against. Deliberately distinct from {@code FAILED},
     * which means an attempt was made and the provider rejected it, errored, or wasn't
     * configured -- conflating the two would make "how often do our pages actually fail"
     * unanswerable. {@code ACCEPTED} means the provider accepted the send, not that it was
     * confirmed delivered.
     */
    public enum ChannelStatus { NOT_ATTEMPTED, ACCEPTED, FAILED }

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

    @Enumerated(EnumType.STRING)
    @Column(name = "sms_status", nullable = false, length = 20)
    private ChannelStatus smsStatus;

    @Column(name = "sms_detail", columnDefinition = "TEXT")
    private String smsDetail;

    @Enumerated(EnumType.STRING)
    @Column(name = "push_status", nullable = false, length = 20)
    private ChannelStatus pushStatus;

    @Column(name = "push_detail", columnDefinition = "TEXT")
    private String pushDetail;

    @Enumerated(EnumType.STRING)
    @Column(name = "slack_status", nullable = false, length = 20)
    private ChannelStatus slackStatus;

    @Column(name = "slack_detail", columnDefinition = "TEXT")
    private String slackDetail;

    @CreationTimestamp
    @Column(name = "escalated_at", nullable = false, updatable = false)
    private Instant escalatedAt;
}
