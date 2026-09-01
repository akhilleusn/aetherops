package com.aetherops.analytics;

import com.aetherops.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsControllerTest {

    @Mock private AnalyticsService analyticsService;

    @InjectMocks
    private AnalyticsController controller;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void getDashboard_noDatesProvided_defaultsToTrailingThirtyDays() {
        when(analyticsService.getDashboard(eq(tenantId), any(), any()))
            .thenReturn(AnalyticsDashboardResponse.builder().tenantId(tenantId).build());

        controller.getDashboard(tenantId, null, null);

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(analyticsService).getDashboard(eq(tenantId), fromCaptor.capture(), toCaptor.capture());

        Duration span = Duration.between(fromCaptor.getValue(), toCaptor.getValue());
        assertThat(span.toDays()).isEqualTo(30);
    }

    @Test
    void getDashboard_explicitDates_passedThroughUnchanged() {
        Instant from = Instant.now().minus(7, ChronoUnit.DAYS);
        Instant to = Instant.now();
        when(analyticsService.getDashboard(tenantId, from, to))
            .thenReturn(AnalyticsDashboardResponse.builder().tenantId(tenantId).build());

        controller.getDashboard(tenantId, from, to);

        verify(analyticsService).getDashboard(tenantId, from, to);
    }

    @Test
    void getDashboard_returnsServiceResultWrappedInApiResponse() {
        AnalyticsDashboardResponse dashboard = AnalyticsDashboardResponse.builder()
            .tenantId(tenantId).totalIncidents(5).build();
        when(analyticsService.getDashboard(eq(tenantId), any(), any())).thenReturn(dashboard);

        ApiResponse<AnalyticsDashboardResponse> response = controller.getDashboard(tenantId, null, null);

        assertThat(response.getData().getTotalIncidents()).isEqualTo(5);
    }

    @Test
    void getDashboard_onlyFromProvided_toDefaultsToNow() {
        Instant from = Instant.now().minus(10, ChronoUnit.DAYS);
        when(analyticsService.getDashboard(eq(tenantId), eq(from), any()))
            .thenReturn(AnalyticsDashboardResponse.builder().tenantId(tenantId).build());

        controller.getDashboard(tenantId, from, null);

        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(analyticsService).getDashboard(eq(tenantId), eq(from), toCaptor.capture());
        assertThat(toCaptor.getValue()).isAfter(from);
    }
}
