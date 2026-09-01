package com.aetherops.usage.model;

import com.aetherops.tenant.model.Plan;

/**
 * Hard-coded monthly operation limits per plan.
 * GROWTH uses Integer.MAX_VALUE internally; the API response converts this to -1.
 */
public final class PlanLimits {

    private PlanLimits() {}

    public static int limitFor(Plan plan, UsageType type) {
        return switch (plan) {
            case FREE    -> freeLimit(type);
            case STARTER -> starterLimit(type);
            case TEAM    -> teamLimit(type);
            case GROWTH  -> Integer.MAX_VALUE;
        };
    }

    /** Returns true when the limit value represents "unlimited". */
    public static boolean isUnlimited(int limit) {
        return limit == Integer.MAX_VALUE;
    }

    /** Converts an internal limit to its API display value (-1 = unlimited). */
    public static int toDisplayLimit(int limit) {
        return isUnlimited(limit) ? -1 : limit;
    }

    private static int freeLimit(UsageType type) {
        return switch (type) {
            case AI_ANALYSIS          -> 5;
            case RUNBOOK_GENERATION   -> 10;
            case POSTMORTEM_GENERATION -> 5;
            case INCIDENT_CREATION    -> 5;
        };
    }

    private static int starterLimit(UsageType type) {
        return switch (type) {
            case AI_ANALYSIS          -> 50;
            case RUNBOOK_GENERATION   -> 50;
            case POSTMORTEM_GENERATION -> 25;
            case INCIDENT_CREATION    -> 50;
        };
    }

    private static int teamLimit(UsageType type) {
        return switch (type) {
            case AI_ANALYSIS          -> 300;
            case RUNBOOK_GENERATION   -> 300;
            case POSTMORTEM_GENERATION -> 150;
            case INCIDENT_CREATION    -> 300;
        };
    }
}
