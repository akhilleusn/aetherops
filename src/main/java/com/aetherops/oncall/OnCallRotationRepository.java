package com.aetherops.oncall;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OnCallRotationRepository extends JpaRepository<OnCallRotation, Long> {
    OnCallRotation findByTenantIdAndServiceId(Long tenantId, String serviceId);
}
