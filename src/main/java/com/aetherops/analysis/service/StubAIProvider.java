package com.aetherops.analysis.service;

import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.analysis.model.AIAnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class StubAIProvider {

    private static final String MODEL          = "stub-v1";
    private static final String PROMPT_VERSION = "stub-1.0";

    public AIAnalysisResult analyze(AIAnalysisContext context) {
        log.info("StubAIProvider returning hardcoded result for incidentId={}", context.getIncidentId());
        return AIAnalysisResult.builder()
                .summary("Stub analysis: incident detected in service '" + context.getServiceId() + "'. "
                        + "This is a hardcoded stub response used when analysis.use-stub=true.")
                .likelyCause("Stub cause: unknown root cause — stub provider does not perform real analysis.")
                .confidenceScore(0.10)
                .evidenceRefs(List.of())
                .unknowns(List.of("All unknowns — stub provider returns hardcoded data only."))
                .recommendedNextChecks(List.of("Disable stub mode and run a real AI analysis."))
                .model(MODEL)
                .promptVersion(PROMPT_VERSION)
                .build();
    }
}
