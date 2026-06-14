package com.aetherops.analysis.service;

import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.analysis.model.AIAnalysisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisRoutingService {

    private final AIProvider aiProvider;
    private final StubAIProvider stubAIProvider;

    @Value("${analysis.use-stub:false}")
    private boolean useStub;

    public AIAnalysisResult analyze(AIAnalysisContext context) {
        if (useStub) {
            log.info("Routing to StubAIProvider (analysis.use-stub=true) incidentId={}", context.getIncidentId());
            return stubAIProvider.analyze(context);
        }
        log.debug("Routing to primary AIProvider incidentId={}", context.getIncidentId());
        return aiProvider.analyze(context);
    }
}
