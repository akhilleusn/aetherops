package com.aetherops.tenant.repository;

import com.aetherops.tenant.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    boolean existsByName(String name);

    Optional<Tenant> findFirstByName(String name);

    Optional<Tenant> findByStripeCustomerId(String stripeCustomerId);
}
