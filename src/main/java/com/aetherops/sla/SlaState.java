package com.aetherops.sla;

public enum SlaState {
    /** Target already met (acknowledged/resolved before the deadline). */
    MET,
    /** Still open, deadline not yet reached, and not close to it. */
    ON_TRACK,
    /** Still open and within the final 20% of the target window before the deadline. */
    AT_RISK,
    /** Deadline passed without the target being met. */
    BREACHED
}
