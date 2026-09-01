package com.aetherops.incident.severity;

public interface SeverityDetector {

    /** Predicts severity from the raw title/description available at incident-creation time. */
    SeverityDetectionResult detect(String title, String description);
}
