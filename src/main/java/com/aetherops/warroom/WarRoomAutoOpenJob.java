package com.aetherops.warroom;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Sweeps every tenant for OPEN CRITICAL incidents with no active war room yet and opens one.
 * A one-minute cadence (tighter than {@code SlaMonitoringJob}'s five) because "still no shared
 * space five minutes into a CRITICAL incident" is already too slow for a P1.
 *
 * There is no WebSocket/push infrastructure in this codebase, so "live" war room state (who's
 * in it, its status) is deliberately poll-based — clients GET {@link WarRoomController} the
 * same way the mobile app pulls incident state, rather than receiving a server push.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WarRoomAutoOpenJob {

    private final TenantRepository tenantRepository;
    private final IncidentRepository incidentRepository;
    private final WarRoomRepository warRoomRepository;
    private final WarRoomService warRoomService;

    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        List<UUID> tenantIds = tenantRepository.findAll().stream().map(Tenant::getId).toList();
        log.debug("Running war room auto-open sweep for {} tenants", tenantIds.size());

        for (UUID tenantId : tenantIds) {
            try {
                TenantContext.set(tenantId);
                sweepTenant(tenantId);
            } catch (Exception e) {
                log.error("War room auto-open sweep failed for tenantId={}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void sweepTenant(UUID tenantId) {
        List<Incident> criticalOpen = incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN).stream()
            .filter(incident -> incident.getSeverity() == IncidentSeverity.CRITICAL)
            .toList();

        for (Incident incident : criticalOpen) {
            if (warRoomRepository.findByIncidentIdAndStatus(incident.getId(), WarRoomStatus.ACTIVE).isPresent()) {
                continue;
            }
            warRoomService.open(tenantId, incident.getId(), "AUTO");
        }
    }
}
