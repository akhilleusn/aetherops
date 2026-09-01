package com.aetherops.oncall;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnCallStatusControllerTest {

    @Mock private OnCallRotationRepository rotationRepository;

    @InjectMocks
    private OnCallStatusController controller;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void getStatus_existingRotation_masksPhoneNumberToLastFourDigits() {
        OnCallRotation rotation = OnCallRotation.builder()
            .id(1L).tenantId(tenantId).serviceId("db-svc").rotationName("DB Team")
            .rotationStartAt(Instant.now()).shiftLengthHours(24)
            .participants(List.of("+15551234567"))
            .build();
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "db-svc")).thenReturn(rotation);

        ApiResponse<OnCallStatusResponse> response = controller.getStatus(tenantId, "db-svc");

        assertThat(response.getData().getCurrentOnCallMasked()).endsWith("4567");
        assertThat(response.getData().getCurrentOnCallMasked()).doesNotContain("+15551234567");
        assertThat(response.getData().getRotationName()).isEqualTo("DB Team");
    }

    @Test
    void getStatus_noRotation_throwsResourceNotFound() {
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "unknown-svc")).thenReturn(null);

        assertThatThrownBy(() -> controller.getStatus(tenantId, "unknown-svc"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getStatus_rotationWithNoParticipants_maskedIsNull() {
        OnCallRotation rotation = OnCallRotation.builder()
            .id(1L).tenantId(tenantId).serviceId("db-svc").rotationName("DB Team")
            .rotationStartAt(Instant.now()).shiftLengthHours(24)
            .participants(List.of())
            .build();
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "db-svc")).thenReturn(rotation);

        ApiResponse<OnCallStatusResponse> response = controller.getStatus(tenantId, "db-svc");

        assertThat(response.getData().getCurrentOnCallMasked()).isNull();
    }

    @Test
    void getStatus_phoneNumberExactlyFourDigits_masksEntirely() {
        OnCallRotation rotation = OnCallRotation.builder()
            .id(1L).tenantId(tenantId).serviceId("db-svc").rotationName("DB Team")
            .rotationStartAt(Instant.now()).shiftLengthHours(24)
            .participants(List.of("1234"))
            .build();
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "db-svc")).thenReturn(rotation);

        ApiResponse<OnCallStatusResponse> response = controller.getStatus(tenantId, "db-svc");

        assertThat(response.getData().getCurrentOnCallMasked()).isEqualTo("••••");
    }

    @Test
    void getStatus_returnsServiceIdFromRotationNotFromPathVariable() {
        OnCallRotation rotation = OnCallRotation.builder()
            .id(1L).tenantId(tenantId).serviceId("db-svc").rotationName("DB Team")
            .rotationStartAt(Instant.now()).shiftLengthHours(24)
            .participants(List.of("+15551234567"))
            .build();
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "db-svc")).thenReturn(rotation);

        ApiResponse<OnCallStatusResponse> response = controller.getStatus(tenantId, "db-svc");

        assertThat(response.getData().getServiceId()).isEqualTo("db-svc");
    }
}
