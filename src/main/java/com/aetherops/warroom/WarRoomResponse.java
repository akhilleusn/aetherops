package com.aetherops.warroom;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class WarRoomResponse {

    private UUID id;
    private UUID tenantId;
    private UUID incidentId;
    private WarRoomStatus status;
    private Instant openedAt;
    private String openedBy;
    private Instant closedAt;
    private String slackChannelId;
    private String slackThreadTs;
    private List<ParticipantResponse> participants;

    @Data
    @Builder
    public static class ParticipantResponse {
        private UUID userId;
        private ParticipantRole role;
        private Instant joinedAt;
    }
}
