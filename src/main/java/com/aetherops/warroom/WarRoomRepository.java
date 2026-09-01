package com.aetherops.warroom;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WarRoomRepository extends JpaRepository<WarRoom, UUID> {

    Optional<WarRoom> findByIncidentIdAndStatus(UUID incidentId, WarRoomStatus status);

    List<WarRoom> findByTenantIdAndStatus(UUID tenantId, WarRoomStatus status);
}
