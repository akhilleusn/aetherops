package com.aetherops.evidence.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class EvidenceItemResponse {

    private UUID id;
    private UUID incidentId;
    private UUID logEventId;
    private EvidenceType type;
    private String sourceRef;
    private String summary;
    private String level;
    private Instant timestamp;
}
