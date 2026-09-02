package com.aetherops.tenant.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.model.OnboardingStatusResponse;
import com.aetherops.tenant.service.OnboardingStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Onboarding", description = "Onboarding-wizard progress tracking")
public class OnboardingController {

    private final OnboardingStatusService onboardingStatusService;

    @GetMapping("/{id}/onboarding/status")
    @Operation(summary = "Report which onboarding step a tenant has reached "
        + "(admin created -> Slack connected -> first incident seen).")
    public ApiResponse<OnboardingStatusResponse> status(@PathVariable UUID id) {
        return ApiResponse.success(onboardingStatusService.getStatus(id));
    }
}
