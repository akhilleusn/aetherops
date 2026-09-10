package com.aetherops.tenant.service;

import com.aetherops.auth.model.RegistrationResult;
import com.aetherops.auth.model.Role;
import com.aetherops.auth.service.InternalTenantProvisioningService;
import com.aetherops.tenant.model.CreateTenantRequest;
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
    public OnboardingResult onboard(String companyName, String adminEmail, String adminPassword) {
        // Phase 2 remediation, finding 7 (see STEP_REPORT.md): this used to take an explicit
        // Plan and pass it through to CreateTenantRequest, but that parameter was already
        // unreachable via any HTTP controller (nothing calls onboard() today) and
        // TenantService.create() now always forces Plan.FREE regardless -- kept as a silently
        // ignored parameter, it would only have misled a future caller. Removed rather than
        // preserved as dead weight; a real "provision a tenant on a specific paid plan"
        // capability, if ever needed, should be its own explicit, reviewed internal action,
        // not a parameter on the public tenant-creation path.
        var response = tenantService.create(new CreateTenantRequest(companyName, 30));

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