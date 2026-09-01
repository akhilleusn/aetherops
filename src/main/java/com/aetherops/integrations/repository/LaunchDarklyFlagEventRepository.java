package com.aetherops.integrations.repository;

import com.aetherops.integrations.model.LaunchDarklyFlagEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LaunchDarklyFlagEventRepository extends JpaRepository<LaunchDarklyFlagEvent, UUID> {

    List<LaunchDarklyFlagEvent> findByTenantIdAndOccurredAtBetweenOrderByOccurredAtDesc(
        UUID tenantId, Instant from, Instant to);
}
