package com.aetherops.analysis.service;

import com.aetherops.analysis.model.AIAnalysisResponse;
import com.aetherops.analysis.model.AnalysisEvaluationResult;
import com.aetherops.analysis.model.RiskLevel;
import com.aetherops.evidence.model.EvidenceItemResponse;
import com.aetherops.evidence.model.EvidenceType;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Evaluates the quality of an AIAnalysisResponse against its evidence pack.
 * Pure logic — no DB, no HTTP, fully unit-testable with new AnalysisEvaluationService().
 */
@Service
public class AnalysisEvaluationService {

    private static final int    TOTAL_CHECKS               = 8;
    private static final double HIGH_CONFIDENCE_THRESHOLD  = 0.70;
    private static final int    WEAK_EVIDENCE_THRESHOLD    = 3;

    private static final List<String> UNSAFE_ACTION_PATTERNS = List.of(
        "drop table", "delete database", "truncate", "rm -rf",
        "disable authentication", "expose secret", "print token",
        "rotate credentials automatically", "restart production repeatedly"
    );

    private static final List<Pattern> SECRET_PATTERNS = List.of(
        // Unmasked Bearer token
        Pattern.compile("(?i)bearer\\s+(?!\\[REDACTED\\])[A-Za-z0-9\\-._~+/]+=*"),
        // Key=value secrets where value is not [REDACTED]
        Pattern.compile("(?i)(?:password|api_key|token|secret)\\s*[=:]\\s*(?!\\[REDACTED\\])[^\\s&,;\"'\\n]+"),
        // AWS IAM access key
        Pattern.compile("AKIA[0-9A-Z]{16}"),
        // Raw JWT (three dot-separated base64url segments)
        Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    );

    // Phrases implying direct access to data sources not in the evidence pack
    private static final List<String> METRIC_CLAIM_INDICATORS = List.of(
        "metrics show", "cpu usage", "memory usage",
        "trace shows", "trace data", "latency spike",
        "throughput", "deployment at", "deployed version"
    );

    private static final List<String> CERTAINTY_WORDS = List.of(
        "definitely", "guaranteed", "proven"
    );

    public AnalysisEvaluationResult evaluate(AIAnalysisResponse analysis, IncidentEvidencePackResponse pack) {
        List<String> issues    = new ArrayList<>();
        RiskLevel    worstRisk = RiskLevel.LOW;

        worstRisk = max(worstRisk, check1_evidenceRefsNotEmptyWithCause(analysis, issues));
        worstRisk = max(worstRisk, check2_evidenceRefsAreReal(analysis, pack, issues));
        worstRisk = max(worstRisk, check3_noUnsupportedDataClaims(analysis, pack, issues));
        worstRisk = max(worstRisk, check4_confidenceCalibration(analysis, pack, issues));
        worstRisk = max(worstRisk, check5_noUnsafeRecommendations(analysis, issues));
        worstRisk = max(worstRisk, check6_noSecretLeakage(analysis, issues));
        worstRisk = max(worstRisk, check7_unknownsAcknowledgeMissingData(analysis, pack, issues));
        worstRisk = max(worstRisk, check8_noCertaintyLanguageWithWeakEvidence(analysis, issues));

        return AnalysisEvaluationResult.builder()
            .passed(issues.isEmpty())
            .issues(List.copyOf(issues))
            .riskLevel(worstRisk)
            .checkedAt(Instant.now())
            .checksPerformed(TOTAL_CHECKS)
            .build();
    }

    // Check 1: evidenceRefs must not be empty when likelyCause is present
    private RiskLevel check1_evidenceRefsNotEmptyWithCause(AIAnalysisResponse analysis, List<String> issues) {
        if (isNonBlank(analysis.getLikelyCause())
                && (analysis.getEvidenceRefs() == null || analysis.getEvidenceRefs().isEmpty())) {
            issues.add("likelyCause is present but evidenceRefs is empty — " +
                "the stated cause has no evidence backing and cannot be verified.");
            return RiskLevel.HIGH;
        }
        return RiskLevel.LOW;
    }

    // Check 2: every ref in evidenceRefs must correspond to a real evidence item in the pack
    private RiskLevel check2_evidenceRefsAreReal(
        AIAnalysisResponse analysis, IncidentEvidencePackResponse pack, List<String> issues
    ) {
        if (analysis.getEvidenceRefs() == null || analysis.getEvidenceRefs().isEmpty()) {
            return RiskLevel.LOW;
        }
        Set<String> realIds = pack.getEvidence().stream()
            .map(e -> e.getId().toString())
            .collect(Collectors.toSet());

        RiskLevel risk = RiskLevel.LOW;
        for (String ref : analysis.getEvidenceRefs()) {
            if (!realIds.contains(ref)) {
                issues.add("evidenceRef '" + ref + "' does not match any evidence item in the pack " +
                    "— this reference appears to be hallucinated.");
                risk = RiskLevel.HIGH;
            }
        }
        return risk;
    }

    // Check 3: likelyCause must not claim data from sources not present in the evidence pack
    private RiskLevel check3_noUnsupportedDataClaims(
        AIAnalysisResponse analysis, IncidentEvidencePackResponse pack, List<String> issues
    ) {
        boolean logOnly = pack.getEvidence().stream()
            .allMatch(e -> e.getType() == EvidenceType.LOG_EVENT);
        if (!logOnly || !isNonBlank(analysis.getLikelyCause())) {
            return RiskLevel.LOW;
        }
        String cause = analysis.getLikelyCause().toLowerCase();
        RiskLevel risk = RiskLevel.LOW;
        for (String indicator : METRIC_CLAIM_INDICATORS) {
            if (cause.contains(indicator)) {
                issues.add("likelyCause references '" + indicator + "', but the evidence pack contains " +
                    "only LOG_EVENT evidence — this claim is not grounded in available data.");
                risk = RiskLevel.HIGH;
            }
        }
        return risk;
    }

    // Check 4: confidenceScore must not be unrealistically high for log-only weak evidence
    private RiskLevel check4_confidenceCalibration(
        AIAnalysisResponse analysis, IncidentEvidencePackResponse pack, List<String> issues
    ) {
        boolean logOnly      = pack.getEvidence().stream()
            .allMatch(e -> e.getType() == EvidenceType.LOG_EVENT);
        boolean weakEvidence = pack.getTotalEvidenceCount() < WEAK_EVIDENCE_THRESHOLD;

        if (logOnly && weakEvidence && analysis.getConfidenceScore() > HIGH_CONFIDENCE_THRESHOLD) {
            issues.add(String.format(
                "confidenceScore %.2f is unrealistically high for log-only evidence with %d item(s) " +
                "— maximum credible confidence for this evidence quality is %.2f.",
                analysis.getConfidenceScore(),
                pack.getTotalEvidenceCount(),
                HIGH_CONFIDENCE_THRESHOLD
            ));
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    // Check 5: recommendedNextChecks must not contain destructive or unsafe actions
    private RiskLevel check5_noUnsafeRecommendations(AIAnalysisResponse analysis, List<String> issues) {
        if (analysis.getRecommendedNextChecks() == null) {
            return RiskLevel.LOW;
        }
        RiskLevel risk = RiskLevel.LOW;
        for (String check : analysis.getRecommendedNextChecks()) {
            String lower = check.toLowerCase();
            for (String unsafe : UNSAFE_ACTION_PATTERNS) {
                if (lower.contains(unsafe)) {
                    issues.add("recommendedNextChecks contains unsafe action: '" + check +
                        "' — matches blocked pattern '" + unsafe + "'.");
                    risk = RiskLevel.HIGH;
                }
            }
        }
        return risk;
    }

    // Check 6: no raw secrets or credentials in any analysis text field
    private RiskLevel check6_noSecretLeakage(AIAnalysisResponse analysis, List<String> issues) {
        for (String text : collectAllTextFields(analysis)) {
            for (Pattern pattern : SECRET_PATTERNS) {
                if (pattern.matcher(text).find()) {
                    issues.add("Potential raw credential or token detected in analysis output — " +
                        "all sensitive values must be masked as [REDACTED] before inclusion in analysis fields.");
                    return RiskLevel.HIGH;
                }
            }
        }
        return RiskLevel.LOW;
    }

    // Check 7: unknowns must acknowledge missing metrics, traces, and deployment data for log-only analysis
    private RiskLevel check7_unknownsAcknowledgeMissingData(
        AIAnalysisResponse analysis, IncidentEvidencePackResponse pack, List<String> issues
    ) {
        boolean logOnly = pack.getEvidence().stream()
            .allMatch(e -> e.getType() == EvidenceType.LOG_EVENT);
        if (!logOnly) {
            return RiskLevel.LOW;
        }
        if (analysis.getUnknowns() == null || analysis.getUnknowns().isEmpty()) {
            issues.add("unknowns is empty for a log-only analysis — " +
                "missing metrics, distributed traces, and deployment history must be acknowledged.");
            return RiskLevel.MEDIUM;
        }
        String unknownsText = String.join(" ", analysis.getUnknowns()).toLowerCase();
        List<String> missing = new ArrayList<>();
        if (!unknownsText.contains("metric"))     missing.add("metrics");
        if (!unknownsText.contains("trace"))      missing.add("distributed traces");
        if (!unknownsText.contains("deployment")) missing.add("deployment history");

        if (!missing.isEmpty()) {
            issues.add("unknowns does not acknowledge missing data source(s): "
                + String.join(", ", missing)
                + " — these must be listed as unknowns for a log-only analysis.");
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    // Check 8: certainty language must not be used when confidenceScore is below the high-confidence threshold
    private RiskLevel check8_noCertaintyLanguageWithWeakEvidence(
        AIAnalysisResponse analysis, List<String> issues
    ) {
        if (analysis.getConfidenceScore() >= HIGH_CONFIDENCE_THRESHOLD) {
            return RiskLevel.LOW;
        }
        String allText = Stream.of(
            analysis.getSummary()     != null ? analysis.getSummary()     : "",
            analysis.getLikelyCause() != null ? analysis.getLikelyCause() : ""
        ).collect(Collectors.joining(" ")).toLowerCase();

        RiskLevel risk = RiskLevel.LOW;
        for (String word : CERTAINTY_WORDS) {
            if (allText.contains(word)) {
                issues.add("analysis contains certainty language '" + word + "' but confidenceScore is "
                    + analysis.getConfidenceScore()
                    + " — strong language must not be used when evidence is weak.");
                risk = RiskLevel.MEDIUM;
            }
        }
        return risk;
    }

    private List<String> collectAllTextFields(AIAnalysisResponse analysis) {
        List<String> fields = new ArrayList<>();
        if (analysis.getSummary()     != null) fields.add(analysis.getSummary());
        if (analysis.getLikelyCause() != null) fields.add(analysis.getLikelyCause());
        if (analysis.getUnknowns()    != null) fields.addAll(analysis.getUnknowns());
        if (analysis.getRecommendedNextChecks() != null) fields.addAll(analysis.getRecommendedNextChecks());
        return fields;
    }

    private boolean isNonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private RiskLevel max(RiskLevel a, RiskLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
