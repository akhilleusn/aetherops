package com.aetherops.incident.severity;

import com.aetherops.incident.model.IncidentSeverity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic keyword-based severity classifier used until a real LLM integration
 * is wired in — same role as {@code MockRunbookGenerator}/{@code MockPostmortemDraftGenerator}
 * for their respective features. Swap by adding another {@link SeverityDetector} bean
 * marked {@code @Primary}.
 */
@Service
public class HeuristicSeverityDetector implements SeverityDetector {

    private static final List<String> CRITICAL_KEYWORDS = List.of(
        "down", "outage", "complete failure", "data loss", "security breach",
        "all users", "cannot log in", "unable to access", "total failure"
    );
    private static final List<String> HIGH_KEYWORDS = List.of(
        "degraded", "failing", "failed", "errors", "unavailable", "timeout", "timing out", "crash"
    );
    private static final List<String> MEDIUM_KEYWORDS = List.of(
        "slow", "latency", "intermittent", "partial", "delayed", "elevated"
    );

    @Override
    public SeverityDetectionResult detect(String title, String description) {
        String text = ((title != null ? title : "") + " " + (description != null ? description : ""))
            .toLowerCase(Locale.ROOT);

        List<String> matched = matches(text, CRITICAL_KEYWORDS);
        if (!matched.isEmpty()) {
            return result(IncidentSeverity.CRITICAL, matched);
        }
        matched = matches(text, HIGH_KEYWORDS);
        if (!matched.isEmpty()) {
            return result(IncidentSeverity.HIGH, matched);
        }
        matched = matches(text, MEDIUM_KEYWORDS);
        if (!matched.isEmpty()) {
            return result(IncidentSeverity.MEDIUM, matched);
        }

        return SeverityDetectionResult.builder()
            .predictedSeverity(IncidentSeverity.LOW)
            .confidenceScore(0.3)
            .reasoning("No severity-indicating keywords found in title/description; defaulted to LOW.")
            .build();
    }

    private List<String> matches(String text, List<String> keywords) {
        return keywords.stream().filter(text::contains).toList();
    }

    private SeverityDetectionResult result(IncidentSeverity severity, List<String> matched) {
        double confidence = Math.min(0.5 + (0.15 * matched.size()), 0.95);
        return SeverityDetectionResult.builder()
            .predictedSeverity(severity)
            .confidenceScore(confidence)
            .reasoning("Matched keyword(s): " + String.join(", ", matched))
            .build();
    }
}
