package com.aetherops.analysis.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class AIAnalysisContext {

    private UUID incidentId;
    private UUID tenantId;
    private String incidentTitle;
    private String serviceId;
    private String severity;
    private String status;
    private Instant firstSeen;
    private Instant lastSeen;
    private List<EvidenceContext> evidenceItems;

    @Data
    @Builder
    public static class EvidenceContext {
        private String evidenceItemId;
        private String type;
        private String summary;   // already masked before reaching this layer
        private String level;
        private Instant timestamp;
    }
}
