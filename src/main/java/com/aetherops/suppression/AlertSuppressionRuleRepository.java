package com.aetherops.suppression;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AlertSuppressionRuleRepository extends JpaRepository<AlertSuppressionRule, UUID> {

    List<AlertSuppressionRule> findByTenantId(UUID tenantId);
}
