package com.aetherops.postmortem.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineEntry {

    private Instant timestamp;

    /** Human-readable description of what happened at this point in time. */
    private String description;

    /** UUID string of the evidence item this entry is grounded in, or null for baseline entries. */
    private String evidenceRef;
}
