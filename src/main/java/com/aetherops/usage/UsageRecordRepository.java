package com.aetherops.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM UsageRecord r " +
            "WHERE r.tenantId = :tenantId AND r.meter = :meter " +
            "AND r.recordedAt >= :from AND r.recordedAt <= :to")
    long sumQuantity(@Param("tenantId") UUID tenantId,
                     @Param("meter") String meter,
                     @Param("from") Instant from,
                     @Param("to") Instant to);
}