package com.aetherops.runbook.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunbookStep {

    private RunbookPhase phase;

    /** Safe, read-only investigative action — must never be destructive. */
    private String action;

    /** Why this step is relevant, grounded in incident context or a specific evidence item. */
    private String rationale;

    /** UUID string of the evidence item this step is grounded in, or null if a baseline step. */
    private String evidenceRef;
}
