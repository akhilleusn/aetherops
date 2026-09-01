package com.aetherops.escalation;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.incident.model.IncidentSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EscalationPolicyControllerTest {

    @Mock private EscalationPolicyService policyService;

    @InjectMocks
    private EscalationPolicyController controller;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void configure_delegatesToServiceAndReturnsResponse() {
        EscalationPolicyRequest request = new EscalationPolicyRequest("db-svc", IncidentSeverity.CRITICAL, 5, true);
        EscalationPolicyResponse serviceResponse = EscalationPolicyResponse.builder().tenantId(tenantId).serviceId("db-svc").build();
        when(policyService.configure(tenantId, request)).thenReturn(serviceResponse);

        ApiResponse<EscalationPolicyResponse> response = controller.configure(tenantId, request);

        assertThat(response.getData().getServiceId()).isEqualTo("db-svc");
    }

    @Test
    void list_delegatesToService() {
        when(policyService.list(tenantId)).thenReturn(List.of(EscalationPolicyResponse.builder().tenantId(tenantId).build()));

        ApiResponse<List<EscalationPolicyResponse>> response = controller.list(tenantId);

        assertThat(response.getData()).hasSize(1);
    }

    @Test
    void delete_delegatesToService() {
        UUID policyId = UUID.randomUUID();

        controller.delete(tenantId, policyId);

        verify(policyService).delete(tenantId, policyId);
    }
}
