package com.aetherops.usage;

import com.aetherops.usage.model.UsageType;
import com.aetherops.usage.service.BillingPeriodService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingPeriodServiceTest {

    @Mock private UsageBillingService billingService;
    @InjectMocks private BillingPeriodService service;

    @Test
    void getCurrentPeriodUsage_includesTenantIdAndPeriodBounds() {
        UUID tenantId = UUID.randomUUID();
        when(billingService.totalForPeriod(eq(tenantId), any(), any(Instant.class), any(Instant.class)))
                .thenReturn(42L);

        Map<String, Object> result = service.getCurrentPeriodUsage(tenantId);

        assertThat(result.get("tenantId")).isEqualTo(tenantId);
        assertThat(result).containsKey("periodStart");
        assertThat(result).containsKey("periodEnd");
        for (UsageType type : UsageType.values()) {
            assertThat(result).containsKey(type.name().toLowerCase());
            assertThat(result.get(type.name().toLowerCase())).isEqualTo(42L);
        }
    }

    @Test
    void getUsageHistory_returnsCorrectNumberOfMonths() {
        UUID tenantId = UUID.randomUUID();
        when(billingService.totalForPeriod(eq(tenantId), any(), any(Instant.class), any(Instant.class)))
                .thenReturn(0L);

        List<Map<String, Object>> history = service.getUsageHistory(tenantId, 3);

        assertThat(history).hasSize(3);
        assertThat(history.get(0)).containsKey("period");
    }

    @Test
    void getProjectedUsage_projectionIsAtLeastActual_whenOnFirstDay() {
        UUID tenantId = UUID.randomUUID();
        when(billingService.totalForPeriod(eq(tenantId), any(), any(Instant.class), any(Instant.class)))
                .thenReturn(10L);

        Map<String, Object> projection = service.getProjectedUsage(tenantId);

        assertThat(projection.get("tenantId")).isEqualTo(tenantId);
        assertThat(projection).containsKey("daysElapsed");
        assertThat(projection).containsKey("totalDaysInMonth");

        for (UsageType type : UsageType.values()) {
            String projKey = type.name().toLowerCase() + "_projected";
            assertThat(projection).containsKey(projKey);
            long projected = ((Long) projection.get(projKey));
            assertThat(projected).isGreaterThanOrEqualTo(10L);
        }
    }

    @Test
    void getProjectedUsage_includesActualValues() {
        UUID tenantId = UUID.randomUUID();
        when(billingService.totalForPeriod(eq(tenantId), any(), any(Instant.class), any(Instant.class)))
                .thenReturn(5L);

        Map<String, Object> projection = service.getProjectedUsage(tenantId);

        for (UsageType type : UsageType.values()) {
            assertThat(projection).containsKey(type.name().toLowerCase() + "_actual");
            assertThat(projection.get(type.name().toLowerCase() + "_actual")).isEqualTo(5L);
        }
    }
}
