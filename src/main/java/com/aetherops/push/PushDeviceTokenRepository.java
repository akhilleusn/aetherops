package com.aetherops.push;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushDeviceTokenRepository extends JpaRepository<PushDeviceToken, UUID> {
    List<PushDeviceToken> findByTenantId(UUID tenantId);
    Optional<PushDeviceToken> findByExpoPushToken(String expoPushToken);
    Optional<PushDeviceToken> findByTenantIdAndExpoPushToken(UUID tenantId, String expoPushToken);
}
