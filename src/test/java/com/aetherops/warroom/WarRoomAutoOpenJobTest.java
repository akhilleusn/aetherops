package com.aetherops.warroom;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WarRoomAutoOpenJobTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private WarRoomRepository warRoomRepository;
    @Mock private WarRoomService warRoomService;

    @InjectMocks
    private WarRoomAutoOpenJob job;

    private final UUID tenantId = UUID.randomUUID();

    private Incident incident(IncidentSeverity severity) {
        return Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).severity(severity)
            .status(IncidentStatus.OPEN).serviceId("db-svc").build();
    }

    @Test
    void sweep_opensWarRoomForCriticalOpenIncidentWithNoActiveWarRoom() {
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id(tenantId).build()));
        Incident critical = incident(IncidentSeverity.CRITICAL);
        when(incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN)).thenReturn(List.of(critical));
        when(warRoomRepository.findByIncidentIdAndStatus(critical.getId(), WarRoomStatus.ACTIVE)).thenReturn(Optional.empty());

        job.sweep();

        verify(warRoomService).open(tenantId, critical.getId(), "AUTO");
    }

    @Test
    void sweep_skipsNonCriticalIncidents() {
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id(tenantId).build()));
        when(incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN))
            .thenReturn(List.of(incident(IncidentSeverity.HIGH), incident(IncidentSeverity.MEDIUM)));

        job.sweep();

        verify(warRoomService, never()).open(any(), any(), any());
    }

    @Test
    void sweep_skipsIncidentsWithExistingActiveWarRoom() {
        when(tenantRepository.findAll()).thenReturn(List.of(Tenant.builder().id(tenantId).build()));
        Incident critical = incident(IncidentSeverity.CRITICAL);
        when(incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN)).thenReturn(List.of(critical));
        when(warRoomRepository.findByIncidentIdAndStatus(critical.getId(), WarRoomStatus.ACTIVE))
            .thenReturn(Optional.of(WarRoom.builder().id(UUID.randomUUID()).build()));

        job.sweep();

        verify(warRoomService, never()).open(any(), any(), any());
    }

    @Test
    void sweep_tenantFailureDoesNotAbortOtherTenants() {
        UUID otherTenantId = UUID.randomUUID();
        when(tenantRepository.findAll()).thenReturn(List.of(
            Tenant.builder().id(tenantId).build(), Tenant.builder().id(otherTenantId).build()));
        when(incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN))
            .thenThrow(new RuntimeException("boom"));
        when(incidentRepository.findByTenantIdAndStatus(otherTenantId, IncidentStatus.OPEN)).thenReturn(List.of());

        job.sweep();

        verify(incidentRepository).findByTenantIdAndStatus(otherTenantId, IncidentStatus.OPEN);
    }
}
