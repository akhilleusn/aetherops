package com.aetherops.oncall;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AlertDeduplicationRepository extends JpaRepository<DeduplicatedAlert, UUID> {

    Optional<DeduplicatedAlert> findByTenantIdAndFingerprint(UUID tenantId, String fingerprint);
}
