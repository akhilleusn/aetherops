package com.aetherops.tenant.service;

import com.aetherops.auth.model.RegisterRequest;
import com.aetherops.auth.service.AuthService;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantOnboardingService {

    private final TenantRepository tenantRepository;
    private final TenantService tenantService;
    private final AuthService authService;

    public record OnboardingResult(UUID tenantId, UUID adminUserId) {}

    @Transactional
    public OnboardingResult onboard(String companyName, Plan plan,
                                    String adminEmail, String adminPassword) {
        // 1. create tenant
        var response = tenantService.create(
                new CreateTenantRequest(companyName, plan, 0, 30));

        // 2. create admin user for that tenant
        var admin = authService.register(
                new RegisterRequest(adminEmail, adminPassword, response.getId()));

        log.info("Onboarded tenant id={} name={} adminEmail={}",
                response.getId(), companyName, adminEmail);

        return new OnboardingResult(response.getId(), admin.getUserId());
    }
}