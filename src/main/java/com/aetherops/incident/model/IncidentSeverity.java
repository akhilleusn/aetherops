package com.aetherops.incident.model;

public enum IncidentSeverity {
    CRITICAL, HIGH, MEDIUM, LOW;

    public boolean isAlertable() {
        return this == CRITICAL || this == HIGH;
    }
}
