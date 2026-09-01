package com.aetherops.cost;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentCostServiceTest {

    @Mock private IncidentCostEntryRepository costEntryRepository;
    @Mock private TenantCostSettingsRepository costSettingsRepository;
    @Mock private IncidentRepository incidentRepository;

    @InjectMocks
    private IncidentCostService costService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    private Incident incident(Instant firstSeen, Instant resolvedAt) {
        return Incident.builder()
            .id(incidentId).tenantId(tenantId).serviceId("db-svc")
            .status(resolvedAt != null ? IncidentStatus.RESOLVED : IncidentStatus.OPEN)
            .severity(IncidentSeverity.CRITICAL)
            .firstSeenAt(firstSeen).lastSeenAt(firstSeen).resolvedAt(resolvedAt)
            .build();
    }

    @Test
    void addEntry_persistsManualEntryWithGivenFields() {
        when(costEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CostEntryResponse response = costService.addEntry(tenantId, incidentId, "Extra EC2 capacity", 34000, "USD", "alice@acme.com");

        assertThat(response.getDescription()).isEqualTo("Extra EC2 capacity");
        assertThat(response.getAmountCents()).isEqualTo(34000);
        assertThat(response.getCurrency()).isEqualTo("USD");
        assertThat(response.getSource()).isEqualTo(CostSource.MANUAL);
        assertThat(response.getRecordedBy()).isEqualTo("alice@acme.com");
    }

    @Test
    void addEntry_blankCurrency_defaultsToUsd() {
        when(costEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CostEntryResponse response = costService.addEntry(tenantId, incidentId, "desc", 100, null, null);

        assertThat(response.getCurrency()).isEqualTo("USD");
    }

    @Test
    void estimateResponseCost_resolvedIncident_computesExactAmount() {
        Instant start = Instant.now().minus(4, ChronoUnit.HOURS);
        Instant end = start.plus(2, ChronoUnit.HOURS);
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident(start, end)));
        when(costSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        long estimate = costService.estimateResponseCost(tenantId, incidentId);

        // 2 hours * default $150/hr (15000 cents) = 30000 cents
        assertThat(estimate).isEqualTo(30000L);
    }

    @Test
    void estimateResponseCost_stillOpenIncident_usesNowAsEndAndIsPositive() {
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident(start, null)));
        when(costSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        long estimate = costService.estimateResponseCost(tenantId, incidentId);

        assertThat(estimate).isPositive();
        // roughly 1 hour at default rate, generous bounds to tolerate test execution time
        assertThat(estimate).isBetween(14000L, 16000L);
    }

    @Test
    void estimateResponseCost_noTenantSettings_usesDefaultRate() {
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident(start, end)));
        when(costSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        long estimate = costService.estimateResponseCost(tenantId, incidentId);

        assertThat(estimate).isCloseTo(15000L, org.assertj.core.data.Offset.offset(50L));
    }

    @Test
    void estimateResponseCost_customTenantRate_isUsedInsteadOfDefault() {
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident(start, end)));
        when(costSettingsRepository.findByTenantId(tenantId)).thenReturn(
            Optional.of(TenantCostSettings.builder().tenantId(tenantId).hourlyResponderRateCents(20000).build()));

        long estimate = costService.estimateResponseCost(tenantId, incidentId);

        assertThat(estimate).isCloseTo(20000L, org.assertj.core.data.Offset.offset(60L));
    }

    @Test
    void estimateResponseCost_unknownIncident_throwsResourceNotFound() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> costService.estimateResponseCost(tenantId, incidentId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void recordAutoEstimate_noExistingEstimate_persistsNewEntry() {
        Instant start = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant end = Instant.now();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident(start, end)));
        when(costSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(costEntryRepository.findByTenantIdAndIncidentIdAndSource(tenantId, incidentId, CostSource.AUTO_ESTIMATE))
            .thenReturn(Optional.empty());
        when(costEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CostEntryResponse response = costService.recordAutoEstimate(tenantId, incidentId, "bob@acme.com");

        assertThat(response.getSource()).isEqualTo(CostSource.AUTO_ESTIMATE);
        assertThat(response.getDescription()).isEqualTo("Estimated responder time cost");
        verify(costEntryRepository, never()).delete(any());
    }

    @Test
    void recordAutoEstimate_existingEstimate_deletesPreviousBeforeInsertingNew() {
        Instant start = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant end = Instant.now();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident(start, end)));
        when(costSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        IncidentCostEntry previous = IncidentCostEntry.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).incidentId(incidentId).source(CostSource.AUTO_ESTIMATE).build();
        when(costEntryRepository.findByTenantIdAndIncidentIdAndSource(tenantId, incidentId, CostSource.AUTO_ESTIMATE))
            .thenReturn(Optional.of(previous));
        when(costEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        costService.recordAutoEstimate(tenantId, incidentId, null);

        verify(costEntryRepository).delete(previous);
        verify(costEntryRepository).save(any());
    }

    @Test
    void getSummary_sumsEntriesAndPicksCurrencyFromFirstRecordedEntry() {
        IncidentCostEntry newest = IncidentCostEntry.builder()
            .id(UUID.randomUUID()).incidentId(incidentId).amountCents(500).currency("EUR")
            .source(CostSource.MANUAL).recordedAt(Instant.now()).build();
        IncidentCostEntry oldest = IncidentCostEntry.builder()
            .id(UUID.randomUUID()).incidentId(incidentId).amountCents(1000).currency("USD")
            .source(CostSource.MANUAL).recordedAt(Instant.now().minus(1, ChronoUnit.HOURS)).build();
        // repository contract: ordered by recordedAt DESC (newest first)
        when(costEntryRepository.findByTenantIdAndIncidentIdOrderByRecordedAtDesc(tenantId, incidentId))
            .thenReturn(List.of(newest, oldest));

        CostSummaryResponse summary = costService.getSummary(tenantId, incidentId);

        assertThat(summary.getTotalCents()).isEqualTo(1500);
        assertThat(summary.getCurrency()).isEqualTo("USD");
        assertThat(summary.getEntries()).hasSize(2);
    }

    @Test
    void getSummary_noEntries_returnsZeroTotalAndDefaultCurrency() {
        when(costEntryRepository.findByTenantIdAndIncidentIdOrderByRecordedAtDesc(tenantId, incidentId))
            .thenReturn(List.of());

        CostSummaryResponse summary = costService.getSummary(tenantId, incidentId);

        assertThat(summary.getTotalCents()).isZero();
        assertThat(summary.getCurrency()).isEqualTo("USD");
        assertThat(summary.getEntries()).isEmpty();
    }

    @Test
    void configureSettings_newTenant_createsSettings() {
        when(costSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(costSettingsRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        TenantCostSettingsResponse response = costService.configureSettings(tenantId, 25000);

        assertThat(response.getTenantId()).isEqualTo(tenantId);
        assertThat(response.getHourlyResponderRateCents()).isEqualTo(25000);
    }

    @Test
    void configureSettings_existingTenant_updatesInPlace() {
        TenantCostSettings existing = TenantCostSettings.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).hourlyResponderRateCents(15000).build();
        when(costSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.of(existing));
        when(costSettingsRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        costService.configureSettings(tenantId, 30000);

        ArgumentCaptor<TenantCostSettings> captor = ArgumentCaptor.forClass(TenantCostSettings.class);
        verify(costSettingsRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
        assertThat(captor.getValue().getHourlyResponderRateCents()).isEqualTo(30000);
    }

    @Test
    void getSettings_unconfiguredTenant_returnsDefaultRate() {
        when(costSettingsRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        TenantCostSettingsResponse response = costService.getSettings(tenantId);

        assertThat(response.getHourlyResponderRateCents()).isEqualTo(15000L);
    }

    @Test
    void getSettings_configuredTenant_returnsStoredRate() {
        when(costSettingsRepository.findByTenantId(tenantId)).thenReturn(
            Optional.of(TenantCostSettings.builder().tenantId(tenantId).hourlyResponderRateCents(22000).build()));

        TenantCostSettingsResponse response = costService.getSettings(tenantId);

        assertThat(response.getHourlyResponderRateCents()).isEqualTo(22000L);
    }
}
