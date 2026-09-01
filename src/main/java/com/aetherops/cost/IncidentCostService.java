package com.aetherops.cost;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentCostService {

    static final long DEFAULT_HOURLY_RATE_CENTS = 15_000L;

    private final IncidentCostEntryRepository costEntryRepository;
    private final TenantCostSettingsRepository costSettingsRepository;
    private final IncidentRepository incidentRepository;

    @Transactional
    public CostEntryResponse addEntry(UUID tenantId, UUID incidentId, String description, long amountCents,
                                       String currency, String recordedBy) {
        IncidentCostEntry entry = IncidentCostEntry.builder()
            .tenantId(tenantId)
            .incidentId(incidentId)
            .description(description)
            .amountCents(amountCents)
            .currency(currency != null && !currency.isBlank() ? currency : "USD")
            .source(CostSource.MANUAL)
            .recordedBy(recordedBy)
            .recordedAt(Instant.now())
            .build();

        IncidentCostEntry saved = costEntryRepository.save(entry);
        log.info("Added manual cost entry tenantId={} incidentId={} amountCents={}", tenantId, incidentId, amountCents);
        return toResponse(saved);
    }

    /**
     * Pure calculation, does not persist — a preview. Callers decide whether to
     * {@link #recordAutoEstimate} it, so an incident that already has a manually-logged
     * responder-time entry doesn't silently double up.
     */
    @Transactional(readOnly = true)
    public long estimateResponseCost(UUID tenantId, UUID incidentId) {
        Incident incident = incidentRepository.findById(incidentId)
            .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + incidentId));

        Instant end = incident.getResolvedAt() != null ? incident.getResolvedAt() : Instant.now();
        Duration duration = Duration.between(incident.getFirstSeenAt(), end);
        double durationHours = duration.toMillis() / 3_600_000.0;

        long hourlyRateCents = costSettingsRepository.findByTenantId(tenantId)
            .map(TenantCostSettings::getHourlyResponderRateCents)
            .orElse(DEFAULT_HOURLY_RATE_CENTS);

        return Math.round(durationHours * hourlyRateCents);
    }

    /**
     * Persists {@link #estimateResponseCost} as one AUTO_ESTIMATE entry, replacing any
     * previous AUTO_ESTIMATE entry for the same incident rather than accumulating duplicates.
     */
    @Transactional
    public CostEntryResponse recordAutoEstimate(UUID tenantId, UUID incidentId, String recordedBy) {
        long estimatedCents = estimateResponseCost(tenantId, incidentId);

        costEntryRepository.findByTenantIdAndIncidentIdAndSource(tenantId, incidentId, CostSource.AUTO_ESTIMATE)
            .ifPresent(costEntryRepository::delete);

        IncidentCostEntry entry = IncidentCostEntry.builder()
            .tenantId(tenantId)
            .incidentId(incidentId)
            .description("Estimated responder time cost")
            .amountCents(estimatedCents)
            .currency("USD")
            .source(CostSource.AUTO_ESTIMATE)
            .recordedBy(recordedBy)
            .recordedAt(Instant.now())
            .build();

        IncidentCostEntry saved = costEntryRepository.save(entry);
        log.info("Recorded auto-estimate cost entry tenantId={} incidentId={} amountCents={}",
            tenantId, incidentId, estimatedCents);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public CostSummaryResponse getSummary(UUID tenantId, UUID incidentId) {
        List<IncidentCostEntry> entries =
            costEntryRepository.findByTenantIdAndIncidentIdOrderByRecordedAtDesc(tenantId, incidentId);

        long totalCents = entries.stream().mapToLong(IncidentCostEntry::getAmountCents).sum();
        String currency = entries.isEmpty() ? "USD" : entries.get(entries.size() - 1).getCurrency();

        return CostSummaryResponse.builder()
            .incidentId(incidentId)
            .entries(entries.stream().map(this::toResponse).toList())
            .totalCents(totalCents)
            .currency(currency)
            .build();
    }

    @Transactional
    public TenantCostSettingsResponse configureSettings(UUID tenantId, long hourlyResponderRateCents) {
        TenantCostSettings settings = costSettingsRepository.findByTenantId(tenantId)
            .orElseGet(() -> TenantCostSettings.builder().tenantId(tenantId).build());

        settings.setHourlyResponderRateCents(hourlyResponderRateCents);

        TenantCostSettings saved = costSettingsRepository.save(settings);
        log.info("Configured cost settings tenantId={} hourlyResponderRateCents={}", tenantId, hourlyResponderRateCents);
        return toSettingsResponse(saved);
    }

    @Transactional(readOnly = true)
    public TenantCostSettingsResponse getSettings(UUID tenantId) {
        return costSettingsRepository.findByTenantId(tenantId)
            .map(this::toSettingsResponse)
            .orElseGet(() -> TenantCostSettingsResponse.builder()
                .tenantId(tenantId)
                .hourlyResponderRateCents(DEFAULT_HOURLY_RATE_CENTS)
                .build());
    }

    private CostEntryResponse toResponse(IncidentCostEntry entry) {
        return CostEntryResponse.builder()
            .id(entry.getId())
            .incidentId(entry.getIncidentId())
            .description(entry.getDescription())
            .amountCents(entry.getAmountCents())
            .currency(entry.getCurrency())
            .source(entry.getSource())
            .recordedBy(entry.getRecordedBy())
            .recordedAt(entry.getRecordedAt())
            .build();
    }

    private TenantCostSettingsResponse toSettingsResponse(TenantCostSettings settings) {
        return TenantCostSettingsResponse.builder()
            .tenantId(settings.getTenantId())
            .hourlyResponderRateCents(settings.getHourlyResponderRateCents())
            .updatedAt(settings.getUpdatedAt())
            .build();
    }
}
