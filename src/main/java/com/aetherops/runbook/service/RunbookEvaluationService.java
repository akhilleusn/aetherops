package com.aetherops.runbook.service;

import com.aetherops.analysis.model.RiskLevel;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.runbook.model.RunbookEvaluationResult;
import com.aetherops.runbook.model.RunbookStep;
import com.aetherops.runbook.model.RunbookSuggestionResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates a runbook suggestion for safety and correctness.
 * Pure logic — no DB, no HTTP, fully unit-testable with new RunbookEvaluationService().
 */
@Service
public class RunbookEvaluationService {

    private static final int TOTAL_CHECKS = 5;

    private static final List<String> UNSAFE_PATTERNS = List.of(
        "drop table", "delete database", "truncate", "rm -rf",
        "disable authentication", "expose secret", "print token",
        "rotate credentials automatically", "restart production repeatedly",
        "kill process", "shutdown service", "wipe", "purge all", "flush all", "format disk"
    );

    private static final List<Pattern> SECRET_PATTERNS = List.of(
        Pattern.compile("(?i)bearer\\s+(?!\\[REDACTED\\])[A-Za-z0-9\\-._~+/]+=*"),
        Pattern.compile("(?i)(?:password|api_key|token|secret)\\s*[=:]\\s*(?!\\[REDACTED\\])[^\\s&,;\"'\\n]+"),
        Pattern.compile("AKIA[0-9A-Z]{16}"),
        Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    );

    public RunbookEvaluationResult evaluate(RunbookSuggestionResponse runbook, IncidentEvidencePackResponse pack) {
        List<String> issues    = new ArrayList<>();
        RiskLevel    worstRisk = RiskLevel.LOW;

        worstRisk = max(worstRisk, check1_stepsNotEmpty(runbook, issues));
        worstRisk = max(worstRisk, check2_eachStepHasActionAndRationale(runbook, issues));
        worstRisk = max(worstRisk, check3_noUnsafeActions(runbook, issues));
        worstRisk = max(worstRisk, check4_noSecretLeakage(runbook, issues));
        worstRisk = max(worstRisk, check5_evidenceRefsAreReal(runbook, pack, issues));

        return RunbookEvaluationResult.builder()
            .passed(issues.isEmpty())
            .issues(List.copyOf(issues))
            .riskLevel(worstRisk)
            .checkedAt(Instant.now())
            .checksPerformed(TOTAL_CHECKS)
            .build();
    }

    // Check 1: runbook must have at least one step
    private RiskLevel check1_stepsNotEmpty(RunbookSuggestionResponse runbook, List<String> issues) {
        if (runbook.getSteps() == null || runbook.getSteps().isEmpty()) {
            issues.add("Runbook contains no steps — at least one TRIAGE step is required.");
            return RiskLevel.HIGH;
        }
        return RiskLevel.LOW;
    }

    // Check 2: every step must have a non-blank action and rationale
    private RiskLevel check2_eachStepHasActionAndRationale(RunbookSuggestionResponse runbook, List<String> issues) {
        if (runbook.getSteps() == null) return RiskLevel.LOW;
        RiskLevel risk = RiskLevel.LOW;
        for (int i = 0; i < runbook.getSteps().size(); i++) {
            RunbookStep step = runbook.getSteps().get(i);
            if (step.getAction() == null || step.getAction().isBlank()) {
                issues.add("Step " + (i + 1) + " has a blank action — every step must have a concrete, non-empty action.");
                risk = RiskLevel.MEDIUM;
            }
            if (step.getRationale() == null || step.getRationale().isBlank()) {
                issues.add("Step " + (i + 1) + " has a blank rationale — every step must explain why it is relevant to the investigation.");
                risk = RiskLevel.MEDIUM;
            }
        }
        return risk;
    }

    // Check 3: no step may contain a destructive or unsafe action
    private RiskLevel check3_noUnsafeActions(RunbookSuggestionResponse runbook, List<String> issues) {
        if (runbook.getSteps() == null) return RiskLevel.LOW;
        RiskLevel risk = RiskLevel.LOW;
        for (RunbookStep step : runbook.getSteps()) {
            String text = stepText(step).toLowerCase();
            for (String pattern : UNSAFE_PATTERNS) {
                if (text.contains(pattern)) {
                    issues.add("Runbook step contains unsafe action pattern '" + pattern
                        + "': \"" + step.getAction() + "\" — runbook steps must be read-only and observational.");
                    risk = RiskLevel.HIGH;
                }
            }
        }
        return risk;
    }

    // Check 4: no raw credentials or tokens in any step text
    private RiskLevel check4_noSecretLeakage(RunbookSuggestionResponse runbook, List<String> issues) {
        if (runbook.getSteps() == null) return RiskLevel.LOW;
        for (RunbookStep step : runbook.getSteps()) {
            String text = stepText(step);
            for (Pattern pattern : SECRET_PATTERNS) {
                if (pattern.matcher(text).find()) {
                    issues.add("Runbook step contains a potential raw credential or token — "
                        + "all sensitive values must be masked as [REDACTED] before inclusion in runbook steps.");
                    return RiskLevel.HIGH;
                }
            }
        }
        return RiskLevel.LOW;
    }

    // Check 5: evidenceRef values in steps must correspond to real evidence items in the pack
    private RiskLevel check5_evidenceRefsAreReal(
        RunbookSuggestionResponse runbook, IncidentEvidencePackResponse pack, List<String> issues
    ) {
        if (runbook.getSteps() == null) return RiskLevel.LOW;
        Set<String> realIds = pack.getEvidence().stream()
            .map(e -> e.getId().toString())
            .collect(Collectors.toSet());

        RiskLevel risk = RiskLevel.LOW;
        for (RunbookStep step : runbook.getSteps()) {
            if (step.getEvidenceRef() != null && !step.getEvidenceRef().isBlank()
                    && !realIds.contains(step.getEvidenceRef())) {
                issues.add("Runbook step evidenceRef '" + step.getEvidenceRef()
                    + "' does not match any evidence item in the pack — this reference appears to be hallucinated.");
                risk = RiskLevel.HIGH;
            }
        }
        return risk;
    }

    private String stepText(RunbookStep step) {
        return (step.getAction() != null ? step.getAction() : "") + " "
            + (step.getRationale() != null ? step.getRationale() : "");
    }

    private RiskLevel max(RiskLevel a, RiskLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
