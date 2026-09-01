package com.aetherops.oncall;

import com.aetherops.incident.model.IncidentSeverity;

import java.time.Duration;

/**
 * On-call routing/paging rules — a distinct vocabulary from IncidentSeverity (which is the
 * customer-facing triage severity). P1 pages immediately and escalates to the next rotation
 * participant if the paged engineer doesn't acknowledge in time.
 */
public enum SeverityLevel {
    P1(true, Duration.ofMinutes(5)),
    P2(true, Duration.ofMinutes(15)),
    P3(false, null),
    P4(false, null);

    public static SeverityLevel fromIncidentSeverity(IncidentSeverity severity) {
        return switch (severity) {
            case CRITICAL -> P1;
            case HIGH -> P2;
            case MEDIUM -> P3;
            case LOW -> P4;
        };
    }

    private final boolean pageImmediately;
    private final Duration escalationWindow;

    SeverityLevel(boolean pageImmediately, Duration escalationWindow) {
        this.pageImmediately = pageImmediately;
        this.escalationWindow = escalationWindow;
    }

    public boolean shouldPageImmediately() {
        return pageImmediately;
    }

    public boolean escalates() {
        return escalationWindow != null;
    }

    /** Null when this severity never escalates (see {@link #escalates()}). */
    public Duration getEscalationWindow() {
        return escalationWindow;
    }
}
