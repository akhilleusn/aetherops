package com.aetherops.demo;

import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.auth.service.JwtService;
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
    private final UserRepository   userRepository;
    private final JwtService       jwtService;

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

    @GetMapping("/auth")
    @Operation(summary = "Return a pre-authenticated JWT for the demo user — no credentials required")
    public ApiResponse<Map<String, Object>> getDemoAuth() {
        if (!demoMode) {
            throw new ResourceNotFoundException("Demo mode is not enabled on this instance");
        }

        Tenant tenant = tenantRepository
            .findFirstByName(DemoDataInitializer.DEMO_TENANT_NAME)
            .orElseThrow(() -> new ResourceNotFoundException("Demo tenant not yet initialised"));

        User demoUser = userRepository
            .findByEmail(DemoDataInitializer.DEMO_USER_EMAIL)
            .orElseThrow(() -> new ResourceNotFoundException("Demo user not yet initialised"));

        String token = jwtService.generateToken(demoUser);

        return ApiResponse.success(Map.of(
            "token",     token,
            "tenantId",  tenant.getId(),
            "email",     demoUser.getEmail(),
            "expiresAt", jwtService.getExpiresAt(jwtService.getExpiryHours()).toString()
        ));
    }
}
