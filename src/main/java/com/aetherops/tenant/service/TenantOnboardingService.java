package com.aetherops.tenant.service;

import com.aetherops.auth.model.RegistrationResult;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.service.InternalTenantProvisioningService;
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
    private final InternalTenantProvisioningService tenantProvisioningService;

    public record OnboardingResult(UUID tenantId, UUID adminUserId) {}

    @Transactional
    public OnboardingResult onboard(String companyName, Plan plan,
                                    String adminEmail, String adminPassword) {
        // 1. create tenant
        var response = tenantService.create(
                new CreateTenantRequest(companyName, plan, 0, 30));

        // 2. create admin user for that tenant — server-derived tenantId (response.getId()),
        // not client input, so this goes through the trusted internal path, not register().
        // Must be Role.OWNER: this is the tenant's own admin, and once role enforcement is
        // live (Phase 1 Step B), OWNER-only actions (billing, SSO config, API keys, tenant
        // deletion) would otherwise be unreachable by the very account that onboarded the
        // tenant. See V63__backfill_tenant_owner_role.sql for existing tenants affected by
        // the prior Role.MEMBER default.
        RegistrationResult admin = tenantProvisioningService.registerIntoTenant(
                adminEmail, adminPassword, response.getId(), Role.OWNER);

        log.info("Onboarded tenant id={} name={} adminEmail={}",
                response.getId(), companyName, adminEmail);

        return new OnboardingResult(response.getId(), admin.userId());
    }
}