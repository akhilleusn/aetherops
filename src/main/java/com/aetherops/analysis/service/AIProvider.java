package com.aetherops.analysis.service;

import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.analysis.model.AIAnalysisResult;

public interface AIProvider {

    AIAnalysisResult analyze(AIAnalysisContext context);
}
