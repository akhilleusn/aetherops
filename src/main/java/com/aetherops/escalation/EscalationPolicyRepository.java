package com.aetherops.escalation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EscalationPolicyRepository extends JpaRepository<EscalationPolicy, UUID> {

    Optional<EscalationPolicy> findByTenantIdAndServiceId(UUID tenantId, String serviceId);

    List<EscalationPolicy> findByTenantId(UUID tenantId);
}
