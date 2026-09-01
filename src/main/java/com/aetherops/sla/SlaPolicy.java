package com.aetherops.sla;

import com.aetherops.incident.model.IncidentSeverity;

import java.time.Duration;

/** Hard-coded response/resolution targets per severity. Same shape as usage.model.PlanLimits. */
public final class SlaPolicy {

    private SlaPolicy() {}

    public static Duration responseTarget(IncidentSeverity severity) {
        return switch (severity) {
            case CRITICAL -> Duration.ofMinutes(5);
            case HIGH -> Duration.ofMinutes(15);
            case MEDIUM -> Duration.ofHours(1);
            case LOW -> Duration.ofHours(4);
        };
    }

    public static Duration resolutionTarget(IncidentSeverity severity) {
        return switch (severity) {
            case CRITICAL -> Duration.ofHours(4);
            case HIGH -> Duration.ofHours(8);
            case MEDIUM -> Duration.ofHours(24);
            case LOW -> Duration.ofHours(72);
        };
    }
}
