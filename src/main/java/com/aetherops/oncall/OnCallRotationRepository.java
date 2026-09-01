package com.aetherops.oncall;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OnCallRotationRepository extends JpaRepository<OnCallRotation, Long> {
    OnCallRotation findByTenantIdAndServiceId(UUID tenantId, String serviceId);
}
