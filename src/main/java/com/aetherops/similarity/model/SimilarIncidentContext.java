package com.aetherops.similarity.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class SimilarIncidentContext {

    private UUID   incidentId;
    private String title;
    private String serviceId;
    private String severity;
    private String resolutionNotes;
    /** Cosine similarity score in [0.0, 1.0]. Higher = more similar. */
    private double similarityScore;
    private Instant resolvedAt;
}
