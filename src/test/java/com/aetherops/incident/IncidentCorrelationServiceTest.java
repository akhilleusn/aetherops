package com.aetherops.incident;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.service.IncidentCorrelationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentCorrelationServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @InjectMocks private IncidentCorrelationService service;

    @Test
    void correlate_returnsEmpty_whenNoCandidatesInWindow() {
        UUID tenantId = UUID.randomUUID();
        when(incidentRepository.findRecentOpenByTenantAndService(
                eq(tenantId), anyString(), eq(IncidentStatus.OPEN), any(Instant.class)))
                .thenReturn(List.of());

        Optional<Incident> result = service.correlate(tenantId, "auth-service", "fingerprint1", Instant.now());

        assertThat(result).isEmpty();
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void correlate_returnsCandidateAndUpdatesIt_whenFoundInWindow() {
        UUID tenantId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        Incident existing = Incident.builder()
                .id(incidentId)
                .tenantId(tenantId)
                .serviceId("auth-service")
                .status(IncidentStatus.OPEN)
                .eventCount(3)
                .firstSeenAt(Instant.now().minusSeconds(120))
                .lastSeenAt(Instant.now().minusSeconds(30))
                .fingerprint("other-fp")
                .severity(com.aetherops.incident.model.IncidentSeverity.MEDIUM)
                .title("Existing incident")
                .build();

        when(incidentRepository.findRecentOpenByTenantAndService(
                eq(tenantId), eq("auth-service"), eq(IncidentStatus.OPEN), any(Instant.class)))
                .thenReturn(List.of(existing));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Incident> result = service.correlate(tenantId, "auth-service", "new-fp", Instant.now());

        assertThat(result).isPresent();
        assertThat(result.get().getEventCount()).isEqualTo(4);
        assertThat(result.get().getCorrelationGroupId()).isEqualTo(incidentId);
    }

    @Test
    void correlate_setsCorrelationGroupId_toSelfIdWhenNull() {
        UUID tenantId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        Incident existing = Incident.builder()
                .id(incidentId)
                .tenantId(tenantId)
                .serviceId("payment-service")
                .status(IncidentStatus.OPEN)
                .eventCount(1)
                .firstSeenAt(Instant.now().minusSeconds(60))
                .lastSeenAt(Instant.now().minusSeconds(10))
                .fingerprint("fp-original")
                .correlationGroupId(null)
                .severity(com.aetherops.incident.model.IncidentSeverity.LOW)
                .title("Payment incident")
                .build();

        when(incidentRepository.findRecentOpenByTenantAndService(
                eq(tenantId), eq("payment-service"), eq(IncidentStatus.OPEN), any(Instant.class)))
                .thenReturn(List.of(existing));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Incident> result = service.correlate(tenantId, "payment-service", "fp-new", Instant.now());

        assertThat(result.get().getCorrelationGroupId()).isEqualTo(incidentId);
    }
}
