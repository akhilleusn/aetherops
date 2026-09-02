package com.aetherops.oncall;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/oncall")
@RequiredArgsConstructor
@Tag(name = "On-Call Status", description = "Current on-call rotation status (mobile app, dashboards)")
public class OnCallStatusController {

    private final OnCallRotationRepository rotationRepository;

    @GetMapping("/{serviceId}")
    @Operation(summary = "Get the current on-call status for a service. Phone number is masked to the last 4 digits.")
    public ApiResponse<OnCallStatusResponse> getStatus(
        @PathVariable UUID tenantId,
        @PathVariable String serviceId
    ) {
        TenantContext.requireOwn(tenantId);
        OnCallRotation rotation = rotationRepository.findByTenantIdAndServiceId(tenantId, serviceId);
        if (rotation == null) {
            throw new ResourceNotFoundException("No on-call rotation found for service: " + serviceId);
        }

        return ApiResponse.success(OnCallStatusResponse.builder()
            .serviceId(rotation.getServiceId())
            .rotationName(rotation.getRotationName())
            .currentOnCallMasked(mask(rotation.currentOnCallPhoneNumber()))
            .build());
    }

    private String mask(String phoneNumber) {
        if (phoneNumber == null) return null;
        if (phoneNumber.length() <= 4) return "••••";
        return "•".repeat(phoneNumber.length() - 4) + phoneNumber.substring(phoneNumber.length() - 4);
    }
}
