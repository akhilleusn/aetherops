package com.aetherops.usage.service;

import com.aetherops.usage.UsageBillingService;
import com.aetherops.usage.model.UsageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillingPeriodService {

    private final UsageBillingService billingService;

    @Transactional(readOnly = true)
    public Map<String, Object> getCurrentPeriodUsage(UUID tenantId) {
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        Instant from = current.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to   = Instant.now();

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("tenantId", tenantId);
        usage.put("periodStart", from);
        usage.put("periodEnd", to);

        for (UsageType type : UsageType.values()) {
            long total = billingService.totalForPeriod(tenantId, type.name(), from, to);
            usage.put(type.name().toLowerCase(), total);
        }

        log.info("Fetched current period usage for tenant={}", tenantId);
        return usage;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getUsageHistory(UUID tenantId, int months) {
        List<Map<String, Object>> history = new ArrayList<>();
        YearMonth current = YearMonth.now(ZoneOffset.UTC);

        for (int i = months - 1; i >= 0; i--) {
            YearMonth period = current.minusMonths(i);
            Instant from = period.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to   = period.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("period", period.toString());
            for (UsageType type : UsageType.values()) {
                long total = billingService.totalForPeriod(tenantId, type.name(), from, to);
                entry.put(type.name().toLowerCase(), total);
            }
            history.add(entry);
        }

        log.info("Fetched {} months usage history for tenant={}", months, tenantId);
        return history;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getProjectedUsage(UUID tenantId) {
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        Instant from = current.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant now  = Instant.now();

        int totalDays   = current.lengthOfMonth();
        int elapsedDays = (int) ChronoUnit.DAYS.between(from, now) + 1;
        double factor   = elapsedDays > 0 ? (double) totalDays / elapsedDays : 1.0;

        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("tenantId", tenantId);
        projection.put("currentMonth", current.toString());
        projection.put("daysElapsed", elapsedDays);
        projection.put("totalDaysInMonth", totalDays);

        for (UsageType type : UsageType.values()) {
            long actual    = billingService.totalForPeriod(tenantId, type.name(), from, now);
            long projected = Math.round(actual * factor);
            projection.put(type.name().toLowerCase() + "_actual", actual);
            projection.put(type.name().toLowerCase() + "_projected", projected);
        }

        log.info("Computed projected usage for tenant={} factor={}", tenantId, factor);
        return projection;
    }
}
