package com.aetherops.oncall;

import java.time.Duration;

/**
 * On-call routing rules per severity. P1 pages immediately and escalates to the
 * next rotation participant if the paged engineer doesn't acknowledge in time.
 */
public enum SeverityLevel {
    P1(true, Duration.ofMinutes(5)),
    P2(true, Duration.ofMinutes(15)),
    P3(false, null),
    P4(false, null);

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
