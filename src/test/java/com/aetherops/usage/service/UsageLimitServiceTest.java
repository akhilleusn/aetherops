package com.aetherops.usage.service;

import com.aetherops.common.exception.UsageLimitExceededException;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.aetherops.usage.model.TenantMonthlyUsage;
import com.aetherops.usage.model.UsageType;
import com.aetherops.usage.repository.TenantMonthlyUsageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsageLimitServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private TenantMonthlyUsageRepository usageRepository;

    @InjectMocks
    private UsageLimitService usageLimitService;

    private Tenant freeTenant(UUID tenantId) {
        return Tenant.builder().id(tenantId).plan(Plan.FREE).build();
    }

    @Test
    void checkAndIncrement_belowLimit_incrementsIncidentCount() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(freeTenant(tenantId)));
        when(usageRepository.findByTenantIdAndPeriod(eq(tenantId), any())).thenReturn(Optional.empty());
        when(usageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        usageLimitService.checkAndIncrement(tenantId, UsageType.INCIDENT_CREATION);

        ArgumentCaptor<TenantMonthlyUsage> captor = ArgumentCaptor.forClass(TenantMonthlyUsage.class);
        verify(usageRepository).save(captor.capture());
        assertThat(captor.getValue().getIncidentCount()).isEqualTo(1);
    }

    @Test
    void checkAndIncrement_atFreeLimit_throwsAndDoesNotIncrement() {
        UUID tenantId = UUID.randomUUID();
        String period = YearMonth.now().toString();
        TenantMonthlyUsage atLimit = TenantMonthlyUsage.builder()
            .tenantId(tenantId).period(period).incidentCount(5).build();

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(freeTenant(tenantId)));
        when(usageRepository.findByTenantIdAndPeriod(tenantId, period)).thenReturn(Optional.of(atLimit));

        assertThatThrownBy(() -> usageLimitService.checkAndIncrement(tenantId, UsageType.INCIDENT_CREATION))
            .isInstanceOf(UsageLimitExceededException.class);

        verify(usageRepository, never()).save(any());
    }

    @Test
    void checkAndIncrement_growthPlan_neverBlocksRegardlessOfCount() {
        UUID tenantId = UUID.randomUUID();
        String period = YearMonth.now().toString();
        Tenant growthTenant = Tenant.builder().id(tenantId).plan(Plan.GROWTH).build();
        TenantMonthlyUsage heavyUsage = TenantMonthlyUsage.builder()
            .tenantId(tenantId).period(period).incidentCount(100_000).build();

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(growthTenant));
        when(usageRepository.findByTenantIdAndPeriod(tenantId, period)).thenReturn(Optional.of(heavyUsage));
        when(usageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        usageLimitService.checkAndIncrement(tenantId, UsageType.INCIDENT_CREATION);

        verify(usageRepository).save(any());
    }

    @Test
    void checkAndIncrement_unregisteredTenant_isAllowedWithoutMetering() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        usageLimitService.checkAndIncrement(tenantId, UsageType.INCIDENT_CREATION);

        verifyNoInteractions(usageRepository);
    }

    @Test
    void getSummary_includesIncidentCreationEntry() {
        UUID tenantId = UUID.randomUUID();
        String period = YearMonth.now().toString();
        TenantMonthlyUsage usage = TenantMonthlyUsage.builder()
            .tenantId(tenantId).period(period).incidentCount(3).build();

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(freeTenant(tenantId)));
        when(usageRepository.findByTenantIdAndPeriod(tenantId, period)).thenReturn(Optional.of(usage));

        var summary = usageLimitService.getSummary(tenantId, null);

        assertThat(summary.getUsage().get(UsageType.INCIDENT_CREATION).getUsed()).isEqualTo(3);
        assertThat(summary.getUsage().get(UsageType.INCIDENT_CREATION).getLimit()).isEqualTo(5);
    }
}
