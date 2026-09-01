package com.aetherops.incident.severity;

import com.aetherops.incident.model.IncidentSeverity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicSeverityDetectorTest {

    private final HeuristicSeverityDetector detector = new HeuristicSeverityDetector();

    @Test
    void detect_titleContainingDown_returnsCritical() {
        SeverityDetectionResult result = detector.detect("Payment service is down", null);
        assertThat(result.getPredictedSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    void detect_dataLossKeyword_returnsCritical() {
        SeverityDetectionResult result = detector.detect("Possible data loss in backup job", null);
        assertThat(result.getPredictedSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    void detect_degradedKeyword_returnsHigh() {
        SeverityDetectionResult result = detector.detect("API performance degraded", null);
        assertThat(result.getPredictedSeverity()).isEqualTo(IncidentSeverity.HIGH);
    }

    @Test
    void detect_timeoutKeyword_returnsHigh() {
        SeverityDetectionResult result = detector.detect("Requests timing out intermittently", null);
        assertThat(result.getPredictedSeverity()).isEqualTo(IncidentSeverity.HIGH);
    }

    @Test
    void detect_latencyKeyword_returnsMedium() {
        SeverityDetectionResult result = detector.detect("Elevated latency on checkout", null);
        assertThat(result.getPredictedSeverity()).isEqualTo(IncidentSeverity.MEDIUM);
    }

    @Test
    void detect_noKeywords_defaultsToLow() {
        SeverityDetectionResult result = detector.detect("Routine maintenance note", "Nothing unusual observed.");
        assertThat(result.getPredictedSeverity()).isEqualTo(IncidentSeverity.LOW);
    }

    @Test
    void detect_criticalKeywordTakesPrecedenceOverHighAndMedium() {
        SeverityDetectionResult result = detector.detect(
            "Service outage causing degraded and slow responses", null);
        assertThat(result.getPredictedSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    void detect_nullDescription_doesNotThrow() {
        SeverityDetectionResult result = detector.detect("Service down", null);
        assertThat(result.getPredictedSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    void detect_isCaseInsensitive() {
        SeverityDetectionResult result = detector.detect("SERVICE IS DOWN", null);
        assertThat(result.getPredictedSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    void detect_moreMatchedKeywords_increasesConfidence() {
        SeverityDetectionResult fewMatches = detector.detect("Service down", null);
        SeverityDetectionResult manyMatches = detector.detect(
            "Complete failure, all users cannot log in, total failure across the board", null);
        assertThat(manyMatches.getConfidenceScore()).isGreaterThan(fewMatches.getConfidenceScore());
    }

    @Test
    void detect_confidenceScore_neverExceedsCap() {
        SeverityDetectionResult result = detector.detect(
            "down outage complete failure data loss security breach all users cannot log in unable to access total failure",
            null);
        assertThat(result.getConfidenceScore()).isLessThanOrEqualTo(0.95);
    }

    @Test
    void detect_lowFallback_hasReasonableConfidence() {
        SeverityDetectionResult result = detector.detect("Nothing notable", null);
        assertThat(result.getConfidenceScore()).isEqualTo(0.3);
        assertThat(result.getReasoning()).contains("defaulted to LOW");
    }
}
