package com.aetherops.postmortem.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PostmortemDraftResult {

    private String incidentSummary;
    private List<TimelineEntry> timeline;
    private String likelyCause;
    private String impact;
    private List<String> dataGaps;
    private List<String> followUpActions;
    private String generatorVersion;
}
