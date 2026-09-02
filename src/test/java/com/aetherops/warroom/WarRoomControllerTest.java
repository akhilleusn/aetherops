package com.aetherops.warroom;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class WarRoomControllerTest {

    @Mock private WarRoomService warRoomService;

    @InjectMocks
    private WarRoomController controller;

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
    private final UUID warRoomId = UUID.randomUUID();

    @Test
    void open_delegatesToServiceWithManualOpenedBy() {
        WarRoomResponse serviceResponse = WarRoomResponse.builder().id(warRoomId).status(WarRoomStatus.ACTIVE).build();
        when(warRoomService.open(tenantId, incidentId, "MANUAL")).thenReturn(serviceResponse);

        ApiResponse<WarRoomResponse> response = controller.open(tenantId, incidentId);

        assertThat(response.getData().getId()).isEqualTo(warRoomId);
        verify(warRoomService).open(tenantId, incidentId, "MANUAL");
    }

    @Test
    void get_delegatesToService() {
        WarRoomResponse serviceResponse = WarRoomResponse.builder().id(warRoomId).build();
        when(warRoomService.get(tenantId, warRoomId)).thenReturn(serviceResponse);

        ApiResponse<WarRoomResponse> response = controller.get(tenantId, warRoomId);

        assertThat(response.getData().getId()).isEqualTo(warRoomId);
    }

    @Test
    void list_defaultsToActiveStatus() {
        when(warRoomService.list(tenantId, WarRoomStatus.ACTIVE)).thenReturn(List.of());

        controller.list(tenantId, WarRoomStatus.ACTIVE);

        verify(warRoomService).list(tenantId, WarRoomStatus.ACTIVE);
    }

    @Test
    void list_explicitClosedStatus_passedThrough() {
        when(warRoomService.list(tenantId, WarRoomStatus.CLOSED)).thenReturn(List.of());

        controller.list(tenantId, WarRoomStatus.CLOSED);

        verify(warRoomService).list(tenantId, WarRoomStatus.CLOSED);
    }

    @Test
    void join_delegatesWithRequestFields() {
        UUID userId = UUID.randomUUID();
        JoinWarRoomRequest request = new JoinWarRoomRequest(userId, ParticipantRole.INCIDENT_COMMANDER);
        WarRoomResponse serviceResponse = WarRoomResponse.builder().id(warRoomId).build();
        when(warRoomService.join(tenantId, warRoomId, userId, ParticipantRole.INCIDENT_COMMANDER)).thenReturn(serviceResponse);

        ApiResponse<WarRoomResponse> response = controller.join(tenantId, warRoomId, request);

        assertThat(response.getData().getId()).isEqualTo(warRoomId);
        verify(warRoomService).join(tenantId, warRoomId, userId, ParticipantRole.INCIDENT_COMMANDER);
    }

    @Test
    void leave_delegatesToService() {
        UUID userId = UUID.randomUUID();
        when(warRoomService.leave(tenantId, warRoomId, userId)).thenReturn(WarRoomResponse.builder().id(warRoomId).build());

        controller.leave(tenantId, warRoomId, userId);

        verify(warRoomService).leave(tenantId, warRoomId, userId);
    }

    @Test
    void close_delegatesToService() {
        when(warRoomService.close(tenantId, warRoomId)).thenReturn(
            WarRoomResponse.builder().id(warRoomId).status(WarRoomStatus.CLOSED).build());

        ApiResponse<WarRoomResponse> response = controller.close(tenantId, warRoomId);

        assertThat(response.getData().getStatus()).isEqualTo(WarRoomStatus.CLOSED);
    }
}
