package com.aetherops.usage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UsageBillingService {

    private final UsageRecordRepository repository;

    @Transactional
    public void record(UUID tenantId, String meter, long quantity) {
        repository.save(UsageRecord.builder()
                .tenantId(tenantId)
                .meter(meter)
                .quantity(quantity)
                .build());
        log.debug("Recorded usage tenantId={} meter={} quantity={}", tenantId, meter, quantity);
    }

    @Transactional(readOnly = true)
    public long totalForPeriod(UUID tenantId, String meter, Instant from, Instant to) {
        return repository.sumQuantity(tenantId, meter, from, to);
    }
}