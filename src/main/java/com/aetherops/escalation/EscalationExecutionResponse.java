package com.aetherops.escalation;

import com.aetherops.escalation.EscalationExecution.ChannelStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class EscalationExecutionResponse {
    private UUID id;
    private UUID incidentId;
    private int escalationLevel;
    private Instant escalatedAt;

    // Step G1 (see STEP_REPORT.md): per-channel delivery outcome, so a human looking at this
    // history can actually tell "SMS failed, push delivered" instead of just "escalation ran".
    private ChannelStatus smsStatus;
    private String smsDetail;
    private ChannelStatus pushStatus;
    private String pushDetail;
    private ChannelStatus slackStatus;
    private String slackDetail;
}
