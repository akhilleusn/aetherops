package com.aetherops.demo;

import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.aetherops.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "demo.mode", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DemoDataInitializer implements ApplicationRunner {

    static final String DEMO_TENANT_NAME = "AetherOps Demo";

    private final TenantRepository tenantRepository;
    private final TenantService    tenantService;

    @Override
    public void run(ApplicationArguments args) {
        Optional<Tenant> existing = tenantRepository.findFirstByName(DEMO_TENANT_NAME);

        UUID tenantId = existing
            .map(Tenant::getId)
            .orElseGet(() -> {
                var response = tenantService.create(new CreateTenantRequest(
                    DEMO_TENANT_NAME, Plan.STARTER, 0, 30));
                return response.getId();
            });

        log.info("================================================");
        log.info("  AetherOps Demo tenant ready");
        log.info("  Tenant ID : {}", tenantId);
        log.info("  Plan      : STARTER  |  Retention: 30 days");
        log.info("  Dashboard : /  (paste the Tenant ID above)");
        log.info("================================================");
    }
}
