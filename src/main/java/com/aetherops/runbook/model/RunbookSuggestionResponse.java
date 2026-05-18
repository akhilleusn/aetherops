package com.aetherops.runbook.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
public class RunbookSuggestionResponse {

    private UUID id;
    private UUID incidentId;
    private UUID tenantId;
    /** Which generation run this is for the incident. First run = 1. */
    private int runbookVersion;
    /** Number of evidence items available at generation time. */
    private int evidenceCount;
    private List<RunbookStep> steps;
    private String generationBasis;
    private String generatorVersion;
    private Instant createdAt;
}
