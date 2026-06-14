package com.aetherops.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsageBillingServiceTest {

    @Mock
    private UsageRecordRepository repository;

    @InjectMocks
    private UsageBillingService service;

    @Test
    void record_savesUsageRecord() {
        UUID tenantId = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.record(tenantId, "AI_ANALYSIS", 1);

        verify(repository).save(argThat(r ->
                r.getTenantId().equals(tenantId) &&
                        r.getMeter().equals("AI_ANALYSIS") &&
                        r.getQuantity() == 1
        ));
    }

    @Test
    void totalForPeriod_returnsSumFromRepository() {
        UUID tenantId = UUID.randomUUID();
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now();
        when(repository.sumQuantity(tenantId, "AI_ANALYSIS", from, to)).thenReturn(42L);

        long total = service.totalForPeriod(tenantId, "AI_ANALYSIS", from, to);

        assertThat(total).isEqualTo(42L);
    }
}