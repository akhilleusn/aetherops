package com.aetherops.statuspage;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.service.model.Service;
import com.aetherops.service.model.ServiceEnvironment;
import com.aetherops.service.repository.ServiceRepository;
import com.aetherops.statuspage.model.StatusPage;
import com.aetherops.statuspage.model.StatusPageIncidentUpdate;
import com.aetherops.statuspage.model.StatusPageUpdateStatus;
import com.aetherops.statuspage.repository.StatusPageIncidentUpdateRepository;
import com.aetherops.statuspage.repository.StatusPageRepository;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Regression test for the dead public status page (see STEP_REPORT.md): resolveTenantIdForSlug
 * ran under ordinary RLS with no app.tenant_id ever set for an anonymous request, so it silently
 * matched zero rows for every slug, forever. Deliberately never calls {@code asTenant(...)}
 * anywhere in this file — a real anonymous visitor has no JWT and no tenant context, which is
 * exactly the condition that broke this endpoint. The only prior coverage of this path
 * (StatusPageServiceTest) mocks the repository entirely and is structurally incapable of
 * exercising RLS either way — this is real Postgres, real RLS, real FORCE ROW LEVEL SECURITY.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicStatusPageIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired StatusPageRepository statusPageRepository;
    @Autowired StatusPageIncidentUpdateRepository updateRepository;
    @Autowired IncidentRepository incidentRepository;
    @Autowired ServiceRepository serviceRepository;

    @Test
    void get_validSlug_noTenantContextAtRequestTime_returnsCorrectTenantsPage() throws Exception {
        UUID tenantId = createTenant();
        String slug = "acme-" + UUID.randomUUID();
        withTenant(tenantId, () -> statusPageRepository.save(StatusPage.builder()
            .tenantId(tenantId).slug(slug).name("Acme Status").build()));
        seedPubliclyVisibleIncident(tenantId, "db-svc", "DB outage",
            IncidentStatus.OPEN, IncidentSeverity.CRITICAL, "Investigating the outage");
        withTenant(tenantId, () -> serviceRepository.save(Service.builder()
            .tenantId(tenantId).projectId(UUID.randomUUID()).name("db-svc")
            .environment(ServiceEnvironment.PRODUCTION).build()));

        // No .with(asTenant(...)) anywhere in this request — a genuinely anonymous call.
        mockMvc.perform(get("/api/v1/status-pages/{slug}", slug))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.pageName").value("Acme Status"))
            .andExpect(jsonPath("$.data.overallStatus").value("MAJOR_OUTAGE"))
            .andExpect(jsonPath("$.data.incidents[0].title").value("DB outage"))
            .andExpect(jsonPath("$.data.incidents[0].updates[0].message").value("Investigating the outage"));
    }

    @Test
    void get_unknownSlug_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/status-pages/{slug}", "does-not-exist-" + UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test
    void get_tenantASlug_neverReturnsTenantBData() throws Exception {
        UUID tenantA = createTenant();
        UUID tenantB = createTenant();
        String slugA = "tenant-a-" + UUID.randomUUID();
        String slugB = "tenant-b-" + UUID.randomUUID();

        withTenant(tenantA, () -> statusPageRepository.save(StatusPage.builder()
            .tenantId(tenantA).slug(slugA).name("Tenant A Page").build()));
        withTenant(tenantB, () -> statusPageRepository.save(StatusPage.builder()
            .tenantId(tenantB).slug(slugB).name("Tenant B Page").build()));

        seedPubliclyVisibleIncident(tenantA, "a-svc", "Tenant A incident",
            IncidentStatus.OPEN, IncidentSeverity.HIGH, "A update");
        seedPubliclyVisibleIncident(tenantB, "b-svc", "Tenant B incident",
            IncidentStatus.OPEN, IncidentSeverity.HIGH, "B update");

        mockMvc.perform(get("/api/v1/status-pages/{slug}", slugA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.pageName").value("Tenant A Page"))
            .andExpect(jsonPath("$.data.incidents", hasSize(1)))
            .andExpect(jsonPath("$.data.incidents[0].title").value("Tenant A incident"));
    }

    // This is the test that actually proves resolve_status_page_tenant's flag reset works — not
    // merely that the whole suite happens to pass. Both calls run inside ONE transaction, with
    // TenantContext set to tenant A throughout: first the bypass resolves tenant B's slug (which
    // must work — that's the function's whole job), then an ORDINARY, RLS-governed query for
    // tenant B's row runs in the SAME transaction. If the flag leaked past the function call,
    // this second query would incorrectly see tenant B's page too.
    @Test
    void resolveTenantIdBySlugBypassingRls_doesNotLeakVisibility_intoSubsequentQueryInSameTransaction() {
        UUID tenantA = createTenant();
        UUID tenantB = createTenant();
        String slugB = "tenant-b-leak-check-" + UUID.randomUUID();
        withTenant(tenantB, () -> statusPageRepository.save(StatusPage.builder()
            .tenantId(tenantB).slug(slugB).name("Tenant B Page").build()));

        LeakCheckResult result = withTenant(tenantA, () -> {
            UUID resolved = statusPageRepository.resolveTenantIdBySlugBypassingRls(slugB);
            var visibleAfterResolving = statusPageRepository.findByTenantId(tenantB);
            return new LeakCheckResult(resolved, visibleAfterResolving);
        });

        assertThat(result.resolvedTenantId()).isEqualTo(tenantB); // sanity: the bypass itself worked
        assertThat(result.visibleTenantBPage()).isEmpty(); // but did NOT leak into the next query
    }

    private record LeakCheckResult(UUID resolvedTenantId, java.util.Optional<StatusPage> visibleTenantBPage) {}

    @Test
    void get_publicResponse_containsNoInternalOnlyFields() throws Exception {
        UUID tenantId = createTenant();
        String slug = "internal-check-" + UUID.randomUUID();
        withTenant(tenantId, () -> statusPageRepository.save(StatusPage.builder()
            .tenantId(tenantId).slug(slug).name("Internal Check").build()));
        seedPubliclyVisibleIncident(tenantId, "svc", "Some incident",
            IncidentStatus.OPEN, IncidentSeverity.HIGH, "public update");

        String body = mockMvc.perform(get("/api/v1/status-pages/{slug}", slug))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // None of these internal-only fields (from Incident or Service, both fully loaded into
        // memory by getPublicPage) may ever appear in a public response, however the DTO
        // mapping evolves later.
        assertThat(body)
            .doesNotContain("resolutionNotes")
            .doesNotContain("slackChannelId")
            .doesNotContain("slackThreadTs")
            .doesNotContain("tenantId")
            .doesNotContain("ownerTeam")
            .doesNotContain("fingerprint")
            .doesNotContain("eventCount")
            .doesNotContain("acknowledgedAt")
            .doesNotContain("correlationGroupId");
    }

    private UUID createTenant() {
        return tenantRepository.save(Tenant.builder()
            .name("Public Status Page Test " + UUID.randomUUID())
            .plan(Plan.STARTER)
            .aiMonthlyBudgetCents(0)
            .retentionDays(30)
            .build()).getId();
    }

    private void seedPubliclyVisibleIncident(UUID tenantId, String serviceId, String title,
                                              IncidentStatus status, IncidentSeverity severity, String updateMessage) {
        Instant now = Instant.now();
        Incident incident = withTenant(tenantId, () -> incidentRepository.save(Incident.builder()
            .tenantId(tenantId)
            .title(title)
            .status(status)
            .severity(severity)
            .serviceId(serviceId)
            .fingerprint(UUID.randomUUID().toString())
            .eventCount(1)
            .firstSeenAt(now)
            .lastSeenAt(now)
            .build()));

        withTenant(tenantId, () -> updateRepository.save(StatusPageIncidentUpdate.builder()
            .incidentId(incident.getId())
            .tenantId(tenantId)
            .status(StatusPageUpdateStatus.INVESTIGATING)
            .message(updateMessage)
            .build()));
    }
}
