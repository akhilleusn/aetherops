package com.aetherops.cost;

import com.aetherops.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentCostControllerTest {

    @Mock private IncidentCostService costService;

    @InjectMocks
    private IncidentCostController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    @Test
    void addEntry_delegatesToServiceWithRequestFields() {
        AddCostEntryRequest request = new AddCostEntryRequest("Extra capacity", 5000, "USD", "alice@acme.com");
        CostEntryResponse serviceResponse = CostEntryResponse.builder().incidentId(incidentId).amountCents(5000).build();
        when(costService.addEntry(tenantId, incidentId, "Extra capacity", 5000, "USD", "alice@acme.com"))
            .thenReturn(serviceResponse);

        ApiResponse<CostEntryResponse> response = controller.addEntry(tenantId, incidentId, request);

        assertThat(response.getData().getAmountCents()).isEqualTo(5000);
        verify(costService).addEntry(tenantId, incidentId, "Extra capacity", 5000, "USD", "alice@acme.com");
    }

    @Test
    void getSummary_delegatesToServiceAndReturnsResponse() {
        CostSummaryResponse serviceResponse = CostSummaryResponse.builder()
            .incidentId(incidentId).entries(List.of()).totalCents(0).currency("USD").build();
        when(costService.getSummary(tenantId, incidentId)).thenReturn(serviceResponse);

        ApiResponse<CostSummaryResponse> response = controller.getSummary(tenantId, incidentId);

        assertThat(response.getData().getIncidentId()).isEqualTo(incidentId);
    }

    @Test
    void getEstimate_returnsEstimatedCentsKey() {
        when(costService.estimateResponseCost(tenantId, incidentId)).thenReturn(42000L);

        ApiResponse<Map<String, Long>> response = controller.getEstimate(tenantId, incidentId);

        assertThat(response.getData()).containsEntry("estimatedCents", 42000L);
    }

    @Test
    void recordEstimate_withRecordedByInBody_passesThrough() {
        CostEntryResponse serviceResponse = CostEntryResponse.builder().incidentId(incidentId).source(CostSource.AUTO_ESTIMATE).build();
        when(costService.recordAutoEstimate(tenantId, incidentId, "carol@acme.com")).thenReturn(serviceResponse);

        ApiResponse<CostEntryResponse> response = controller.recordEstimate(tenantId, incidentId, Map.of("recordedBy", "carol@acme.com"));

        assertThat(response.getData().getSource()).isEqualTo(CostSource.AUTO_ESTIMATE);
    }

    @Test
    void recordEstimate_nullBody_passesNullRecordedBy() {
        CostEntryResponse serviceResponse = CostEntryResponse.builder().incidentId(incidentId).source(CostSource.AUTO_ESTIMATE).build();
        when(costService.recordAutoEstimate(tenantId, incidentId, null)).thenReturn(serviceResponse);

        controller.recordEstimate(tenantId, incidentId, null);

        verify(costService).recordAutoEstimate(tenantId, incidentId, null);
    }
}
