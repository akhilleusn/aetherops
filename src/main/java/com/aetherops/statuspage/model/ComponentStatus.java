package com.aetherops.statuspage.model;

/** Ordered worst-to-best is NOT the enum order — see StatusPageService for severity ranking. */
public enum ComponentStatus {
    OPERATIONAL, DEGRADED_PERFORMANCE, PARTIAL_OUTAGE, MAJOR_OUTAGE
}
