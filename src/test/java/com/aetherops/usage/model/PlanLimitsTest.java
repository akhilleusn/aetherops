package com.aetherops.usage.model;

import com.aetherops.tenant.model.Plan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanLimitsTest {

    @Test
    void freeTier_incidentCreationLimit_isFive() {
        assertThat(PlanLimits.limitFor(Plan.FREE, UsageType.INCIDENT_CREATION)).isEqualTo(5);
    }

    @Test
    void starterTier_incidentCreationLimit_isFifty() {
        assertThat(PlanLimits.limitFor(Plan.STARTER, UsageType.INCIDENT_CREATION)).isEqualTo(50);
    }

    @Test
    void teamTier_incidentCreationLimit_isThreeHundred() {
        assertThat(PlanLimits.limitFor(Plan.TEAM, UsageType.INCIDENT_CREATION)).isEqualTo(300);
    }

    @Test
    void growthTier_incidentCreation_isUnlimited() {
        int limit = PlanLimits.limitFor(Plan.GROWTH, UsageType.INCIDENT_CREATION);
        assertThat(PlanLimits.isUnlimited(limit)).isTrue();
        assertThat(PlanLimits.toDisplayLimit(limit)).isEqualTo(-1);
    }

    @Test
    void everyPlanAndUsageTypeCombination_hasALimit() {
        for (Plan plan : Plan.values()) {
            for (UsageType type : UsageType.values()) {
                int limit = PlanLimits.limitFor(plan, type);
                assertThat(limit).isPositive();
            }
        }
    }

    @Test
    void higherTiers_neverHaveALowerLimitThanFree() {
        for (UsageType type : UsageType.values()) {
            int free = PlanLimits.limitFor(Plan.FREE, type);
            int starter = PlanLimits.limitFor(Plan.STARTER, type);
            int team = PlanLimits.limitFor(Plan.TEAM, type);
            assertThat(starter).isGreaterThanOrEqualTo(free);
            assertThat(team).isGreaterThanOrEqualTo(starter);
        }
    }
}
