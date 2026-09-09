package com.aetherops.statuspage.repository;

import com.aetherops.statuspage.model.StatusPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StatusPageRepository extends JpaRepository<StatusPage, UUID> {
    Optional<StatusPage> findBySlug(String slug);
    Optional<StatusPage> findByTenantId(UUID tenantId);

    /**
     * The ONLY sanctioned way to read status_pages without an app.tenant_id already set — see
     * V68's javadoc and {@code resolve_status_page_tenant}'s own COMMENT ON FUNCTION. Returns
     * a single UUID column, nothing else; never use this for anything but the public status
     * page's initial slug -> tenant resolution.
     */
    @Query(value = "SELECT resolve_status_page_tenant(:slug)", nativeQuery = true)
    UUID resolveTenantIdBySlugBypassingRls(@Param("slug") String slug);
}
