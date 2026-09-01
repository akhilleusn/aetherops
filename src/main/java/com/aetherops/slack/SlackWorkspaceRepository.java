package com.aetherops.slack;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SlackWorkspaceRepository extends JpaRepository<SlackWorkspace, UUID> {
    Optional<SlackWorkspace> findByTenantId(UUID tenantId);
    Optional<SlackWorkspace> findByTeamId(String teamId);
}
