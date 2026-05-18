package com.aetherops.runbook.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RunbookGenerationResult {

    private List<RunbookStep> steps;

    /** Describes what data sources the runbook was generated from — e.g. "log-only", "mixed-evidence", "no-evidence". */
    private String generationBasis;

    private String generatorVersion;
}
