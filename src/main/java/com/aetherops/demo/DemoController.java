package com.aetherops.demo;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/demo")
@RequiredArgsConstructor
@Tag(name = "Demo", description = "Public demo account information")
public class DemoController {

    private final TenantRepository tenantRepository;

    @Value("${demo.mode:false}")
    private boolean demoMode;

    @GetMapping("/tenant")
    @Operation(summary = "Return the demo tenant ID when demo mode is active")
    public ApiResponse<Map<String, Object>> getDemoTenant() {
        if (!demoMode) {
            throw new ResourceNotFoundException("Demo mode is not enabled on this instance");
        }
        Tenant tenant = tenantRepository
            .findFirstByName(DemoDataInitializer.DEMO_TENANT_NAME)
            .orElseThrow(() -> new ResourceNotFoundException("Demo tenant not yet initialised"));

        return ApiResponse.success(Map.of(
            "id",   tenant.getId(),
            "name", tenant.getName(),
            "plan", tenant.getPlan()
        ));
    }
}
