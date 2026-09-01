package com.aetherops.sla;

import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaMonitoringJobTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private SlaTrackingService slaTrackingService;

    @InjectMocks
    private SlaMonitoringJob job;

    @Test
    void checkAllTenants_callsCheckForEveryTenant() {
        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();
        when(tenantRepository.findAll()).thenReturn(List.of(
            Tenant.builder().id(tenant1).plan(Plan.FREE).build(),
            Tenant.builder().id(tenant2).plan(Plan.STARTER).build()));

        job.checkAllTenants();

        verify(slaTrackingService).checkAndMarkBreaches(tenant1);
        verify(slaTrackingService).checkAndMarkBreaches(tenant2);
    }

    @Test
    void checkAllTenants_clearsTenantContextAfterEachTenant() {
        UUID tenant1 = UUID.randomUUID();
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id(tenant1).plan(Plan.FREE).build()));

        job.checkAllTenants();

        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void checkAllTenants_oneTenantThrows_stillProcessesTheRest() {
        UUID failingTenant = UUID.randomUUID();
        UUID okTenant = UUID.randomUUID();
        when(tenantRepository.findAll()).thenReturn(List.of(
            Tenant.builder().id(failingTenant).plan(Plan.FREE).build(),
            Tenant.builder().id(okTenant).plan(Plan.FREE).build()));
        doThrow(new RuntimeException("boom")).when(slaTrackingService).checkAndMarkBreaches(failingTenant);

        job.checkAllTenants();

        verify(slaTrackingService).checkAndMarkBreaches(okTenant);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void checkAllTenants_noTenants_doesNothing() {
        when(tenantRepository.findAll()).thenReturn(List.of());

        job.checkAllTenants();

        verifyNoInteractions(slaTrackingService);
    }
}
