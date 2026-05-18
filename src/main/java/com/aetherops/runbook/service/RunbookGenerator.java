package com.aetherops.runbook.service;

import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.runbook.model.RunbookGenerationResult;

public interface RunbookGenerator {

    RunbookGenerationResult generate(AIAnalysisContext context);
}
