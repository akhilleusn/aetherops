package com.aetherops.tenant;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentResponse;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.service.IncidentService;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 Step D, item D0(b) follow-up. TenantContextInterceptor's {@code @Before} advice
 * and Spring's own transactional advisor both default to {@code @Order(Ordered.LOWEST_PRECEDENCE)}
 * — an AOP ordering TIE. AbstractIntegrationTest's javadoc documents that the interceptor
 * "reliably wins" that tie for user-defined {@code @Transactional} service methods, but that
 * was asserted, not proven by a dedicated test. This proves it directly against
 * {@code IncidentService.findById}, which has ZERO application-layer tenant filtering —
 * {@code app.tenant_id}-driven RLS is its ONLY protection, making it the most direct possible
 * probe of whether that session variable is actually set before the query runs.
 *
 * If the interceptor lost the ordering race, {@code app.tenant_id} would still be unset
 * (NULL) at query time. Postgres's policy predicate
 * {@code tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid} compares
 * against NULL, which is never true in SQL — so RLS would filter out EVERY row, including
 * the caller's own tenant's incident. That is the observable signature of the ordering bug:
 * even fetching your own incident by ID would incorrectly 404.
 */
@ActiveProfiles("test")
class TenantContextOrderingTest extends AbstractIntegrationTest {

    @Autowired IncidentService incidentService;
    @Autowired IncidentRepository incidentRepository;
    @Autowired TenantRepository tenantRepository;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void tenantContextInterceptor_setsAppTenantIdBeforeQuery_ownIncidentIsFound() {
        UUID tenantId = createTenant();
        UUID incidentId = seedIncident(tenantId);

        // The ordering-dependent step: findById() has no application-layer tenant filter
        // at all — if app.tenant_id isn't set by the time this SELECT runs, RLS returns
        // zero rows even for the caller's own incident.
        TenantContext.set(tenantId);
        IncidentResponse response = incidentService.findById(incidentId);

        assertThat(response.getId()).isEqualTo(incidentId);
    }

    @Test
    void tenantContextInterceptor_setsAppTenantIdBeforeQuery_otherTenantIncidentIsNotFound() {
        UUID tenantA = createTenant();
        UUID tenantB = createTenant();
        UUID incidentBId = seedIncident(tenantB);

        // Proves RLS is actively filtering (not merely "everything visible regardless") —
        // combined with the positive test above, this pins down that app.tenant_id is set
        // to exactly the CALLING tenant at query time, not null and not stale.
        TenantContext.set(tenantA);
        assertThatThrownBy(() -> incidentService.findById(incidentBId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    private UUID createTenant() {
        return tenantRepository.save(Tenant.builder()
            .name("Ordering Test " + UUID.randomUUID())
            .plan(Plan.STARTER)
            .aiMonthlyBudgetCents(0)
            .retentionDays(30)
            .build()).getId();
    }

    private UUID seedIncident(UUID tenantId) {
        Instant now = Instant.now();
        Incident incident = withTenant(tenantId, () -> incidentRepository.save(Incident.builder()
            .tenantId(tenantId)
            .title("Ordering test incident")
            .status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.LOW)
            .serviceId("ordering-test-svc")
            .fingerprint(UUID.randomUUID().toString())
            .eventCount(1)
            .firstSeenAt(now)
            .lastSeenAt(now)
            .build()));
        return incident.getId();
    }
}
