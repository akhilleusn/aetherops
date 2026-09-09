package com.aetherops.statuspage.repository;

import com.aetherops.statuspage.model.StatusPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StatusPageRepository extends JpaRepository<StatusPage, UUID> {
    Optional<StatusPage> findBySlug(String slug);
    Optional<StatusPage> findByTenantId(UUID tenantId);
}
