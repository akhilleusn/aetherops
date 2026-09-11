package com.aetherops.escalation;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EscalationHistoryControllerTest {

    @Mock private EscalationExecutionRepository executionRepository;

    @InjectMocks
    private EscalationHistoryController controller;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setTenantContext() {
        TenantContext.set(tenantId);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }
    private final UUID incidentId = UUID.randomUUID();

    @Test
    void history_mapsExecutionsToResponsesInOrder() {
        EscalationExecution level1 = EscalationExecution.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).incidentId(incidentId).escalationLevel(1).escalatedAt(Instant.now()).build();
        EscalationExecution level2 = EscalationExecution.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).incidentId(incidentId).escalationLevel(2).escalatedAt(Instant.now()).build();
        when(executionRepository.findByTenantIdAndIncidentIdOrderByEscalationLevelAsc(tenantId, incidentId))
            .thenReturn(List.of(level1, level2));

        ApiResponse<List<EscalationExecutionResponse>> response = controller.history(tenantId, incidentId);

        assertThat(response.getData()).hasSize(2);
        assertThat(response.getData().get(0).getEscalationLevel()).isEqualTo(1);
        assertThat(response.getData().get(1).getEscalationLevel()).isEqualTo(2);
    }

    // Step G1 (see STEP_REPORT.md): per-channel delivery status must actually reach the API,
    // not just sit in the database -- otherwise "SMS failed, push delivered" is still invisible
    // to whoever is looking at this history at 3am.
    @Test
    void history_includesPerChannelDeliveryStatus() {
        EscalationExecution execution = EscalationExecution.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).incidentId(incidentId).escalationLevel(1)
            .escalatedAt(Instant.now())
            .smsStatus(EscalationExecution.ChannelStatus.FAILED).smsDetail("PROVIDER_ERROR: boom")
            .pushStatus(EscalationExecution.ChannelStatus.ACCEPTED)
            .slackStatus(EscalationExecution.ChannelStatus.NOT_ATTEMPTED)
            .build();
        when(executionRepository.findByTenantIdAndIncidentIdOrderByEscalationLevelAsc(tenantId, incidentId))
            .thenReturn(List.of(execution));

        ApiResponse<List<EscalationExecutionResponse>> response = controller.history(tenantId, incidentId);

        EscalationExecutionResponse mapped = response.getData().get(0);
        assertThat(mapped.getSmsStatus()).isEqualTo(EscalationExecution.ChannelStatus.FAILED);
        assertThat(mapped.getSmsDetail()).isEqualTo("PROVIDER_ERROR: boom");
        assertThat(mapped.getPushStatus()).isEqualTo(EscalationExecution.ChannelStatus.ACCEPTED);
        assertThat(mapped.getSlackStatus()).isEqualTo(EscalationExecution.ChannelStatus.NOT_ATTEMPTED);
    }

    @Test
    void history_noExecutions_returnsEmptyList() {
        when(executionRepository.findByTenantIdAndIncidentIdOrderByEscalationLevelAsc(tenantId, incidentId))
            .thenReturn(List.of());

        ApiResponse<List<EscalationExecutionResponse>> response = controller.history(tenantId, incidentId);

        assertThat(response.getData()).isEmpty();
    }
}
