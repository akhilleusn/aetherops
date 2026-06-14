package com.aetherops.incident.service;

import com.aetherops.incident.model.CreateIncidentRequest;
import com.aetherops.incident.model.IncidentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotentIncidentService {

    private final IncidentService incidentService;
    private final RedissonClient redissonClient;

    public IncidentResponse create(CreateIncidentRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return incidentService.create(request, null);
        }

        String lockKey = "lock:incident:idem:" + idempotencyKey;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(5, 5, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException(
                        "Could not acquire distributed lock for idempotency key: " + idempotencyKey);
            }
            log.debug("Acquired distributed lock for idempotencyKey={}", idempotencyKey);
            return incidentService.create(request, idempotencyKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lock acquisition interrupted for key: " + idempotencyKey, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Released distributed lock for idempotencyKey={}", idempotencyKey);
            }
        }
    }
}
