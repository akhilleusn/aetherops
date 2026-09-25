package com.aetherops.publicdemo;

import com.aetherops.analysis.model.AIAnalysis;
import com.aetherops.analysis.model.AnalysisStatus;
import com.aetherops.apikey.model.CreateApiKeyRequest;
import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.project.model.Project;
import com.aetherops.project.repository.ProjectRepository;
import com.aetherops.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
    "app.public-demo.tenant-id=d0000000-0000-4000-8000-000000000001",
    "app.public-demo.requests-per-window=100000"   // the limit itself is PublicDemoRateLimitTest's job
})
@Import(PublicDemoSecurityTest.ReadOnlyProbe.class)
class PublicDemoSecurityTest extends AbstractPublicDemoTest {

    // "description" is not listed: postmortem timeline entries legitimately have one. The incident's
    // own (internal) description is covered by incidentViews_haveNoDescriptionField and the
    // "internal description" content check.
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
        "tenantId", "projectId", "fingerprint", "latestEventId", "tags", "resolutionNotes",
        "slackChannelId", "slackThreadTs", "errorMessage", "similarIncidents", "similarIncidentIds",
        "slackDiscussionSummary", "rawPayload", "traceId", "spanId", "logEventId");

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;
    @Autowired Environment       environment;
    @Autowired ReadOnlyProbe     readOnlyProbe;
    @Autowired ObjectMapper      objectMapper;
    @Autowired ProjectRepository projectRepository;
    @Autowired ApiKeyService     apiKeyService;

    private SeededIncident demo;
    private SeededIncident other;

    @BeforeEach
    void seed() {
        demo  = seedIncident(DEMO_TENANT, "Demo: connection pool exhausted");
        other = seedIncident(OTHER_TENANT, "PRIVATE customer outage");
    }

    // ── 1. Route inventory ─────────────────────────────────────────────────

    @Test
    void routeInventory_everyDemoRouteIsGetOnly_andServedOnlyByPublicDemoController() {
        Map<RequestMappingInfo, HandlerMethod> routes = demoRoutes();
        assertThat(routes).isNotEmpty();
        routes.forEach((info, handler) -> {
            assertThat(handler.getBeanType()).as(info.toString()).isEqualTo(PublicDemoController.class);
            // An empty methods condition means "any HTTP method" -- must never happen here.
            assertThat(info.getMethodsCondition().getMethods()).as(info.toString()).containsExactly(RequestMethod.GET);
        });
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (handler.getBeanType() == PublicDemoController.class) {
                assertThat(info.getPatternValues()).as(info.toString()).allMatch(p -> p.startsWith(PREFIX + "/"));
            }
        });
    }

    @Test
    void routeInventory_everyPublicDemoServiceMethodIsReadOnlyTransactional() {
        List<Method> methods = Arrays.stream(PublicDemoService.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .toList();
        assertThat(methods).isNotEmpty();
        for (Method m : methods) {
            Transactional tx = m.getAnnotation(Transactional.class);
            assertThat(tx).as(m.getName() + " is @Transactional").isNotNull();
            assertThat(tx.readOnly()).as(m.getName() + " is readOnly").isTrue();
        }
    }

    // ── 2. Method matrix ───────────────────────────────────────────────────

    @Test
    void everyNonGetMethod_onEveryDemoRoute_is405_andChangesNothing() throws Exception {
        String before = databaseFingerprint();
        for (String path : concreteDemoPaths(demo.incidentId())) {
            for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
                                             HttpMethod.DELETE, HttpMethod.OPTIONS, HttpMethod.HEAD)) {
                mockMvc.perform(request(method, path).contentType(APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(header().string(HttpHeaders.ALLOW, "GET"));
            }
        }
        assertThat(databaseFingerprint()).isEqualTo(before);
    }

    // ── 3. Credentials rejected, none issued ───────────────────────────────

    @Test
    void anyCredential_onAnyDemoRoute_is400_andNoTokenIsEverReturned() throws Exception {
        String validApiKey = createApiKey(DEMO_TENANT);
        List<RequestPostProcessor> credentials = List.of(
            asTenant(DEMO_TENANT),                                                   // valid JWT, demo tenant
            asTenant(OTHER_TENANT),                                                  // valid JWT, other tenant
            r -> { r.addHeader("Authorization", "Bearer not-a-jwt"); return r; },
            r -> { r.addHeader("Authorization", "Basic dXNlcjpwYXNz"); return r; },
            r -> { r.addHeader("X-Api-Key", validApiKey); return r; },              // valid API key
            r -> { r.addHeader("X-Api-Key", "ak_garbage"); return r; });
        for (String path : concreteDemoPaths(demo.incidentId())) {
            for (RequestPostProcessor credential : credentials) {
                String body = mockMvc.perform(get(path).with(credential))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
                assertThat(body).as(path).doesNotContain("eyJ").doesNotContainIgnoringCase("token");
            }
            MvcResult plain = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
            assertThat(plain.getResponse().getContentAsString()).as(path).doesNotContain("eyJ");
            assertThat(plain.getResponse().getHeader(HttpHeaders.SET_COOKIE)).as(path).isNull();
            assertThat(plain.getResponse().getHeader(HttpHeaders.AUTHORIZATION)).as(path).isNull();
        }
    }

    // ── 4. Nothing outside the prefix becomes reachable ────────────────────

    @Test
    void demoAccess_grantsNothingOutsideThePrefix() throws Exception {
        String t = DEMO_TENANT.toString();
        String id = demo.incidentId().toString();
        mockMvc.perform(get("/api/v1/incidents").param("tenantId", t)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/incidents/" + id).param("tenantId", t)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/incidents/" + id + "/analyze/latest").param("tenantId", t)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/incidents/" + id + "/analyze").param("tenantId", t)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/incidents/" + id).param("tenantId", t).contentType(APPLICATION_JSON)
            .content("{\"status\":\"CLOSED\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/events").contentType(APPLICATION_JSON)
            .content("{\"serviceId\":\"x1\",\"level\":\"ERROR\",\"message\":\"m\",\"occurredAt\":\"2026-09-25T00:00:00Z\"}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/tenants")).andExpect(status().isUnauthorized());
    }

    @Test
    void demoRequest_leavesNoTenantContextBehind() throws Exception {
        mockMvc.perform(get(PREFIX + "/incidents")).andExpect(status().isOk());
        assertThat(TenantContext.get()).isNull();
        mockMvc.perform(get("/api/v1/incidents").param("tenantId", DEMO_TENANT.toString()))
            .andExpect(status().isUnauthorized());
    }

    // ── 5. Tenant pinning ──────────────────────────────────────────────────

    @Test
    void demoRoutes_onlyEverServeTheDemoTenant() throws Exception {
        String list = body(get(PREFIX + "/incidents"));
        assertThat(list).contains(demo.incidentId().toString())
            .doesNotContain(other.incidentId().toString()).doesNotContain("PRIVATE");

        // Caller-supplied tenant hints are ignored.
        String hinted = body(get(PREFIX + "/incidents")
            .param("tenantId", OTHER_TENANT.toString()).header("X-Tenant-Id", OTHER_TENANT.toString()));
        assertThat(hinted).doesNotContain(other.incidentId().toString());

        for (String path : concreteDemoPaths(other.incidentId())) {
            if (!path.equals(PREFIX + "/incidents")) {
                mockMvc.perform(get(path)).andExpect(status().isNotFound());
            }
        }
        for (String path : concreteDemoPaths(demo.incidentId())) {   // positive control
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    // ── 6. Read-only at the database ───────────────────────────────────────

    @Test
    void readingEveryDemoRoute_writesNothingToAnyTable() throws Exception {
        String before = databaseFingerprint();
        for (int i = 0; i < 3; i++) {
            for (String path : concreteDemoPaths(demo.incidentId())) {
                mockMvc.perform(get(path)).andExpect(status().isOk());
            }
        }
        assertThat(databaseFingerprint()).isEqualTo(before);
    }

    @Test
    void readOnlyTransaction_isRejectedByPostgresOnWrite() {
        assertThatThrownBy(readOnlyProbe::attemptWrite).hasStackTraceContaining("read-only transaction");
    }

    // ── 8. Field allow-list ────────────────────────────────────────────────

    @Test
    void demoResponses_exposeOnlyAllowListedFields() throws Exception {
        for (String path : concreteDemoPaths(demo.incidentId())) {
            String body = body(get(path));
            assertThat(fieldNames(objectMapper.readTree(body))).as(path).doesNotContainAnyElementsOf(FORBIDDEN_FIELDS);
            assertThat(body).as(path)
                .doesNotContain("@example.com")
                .doesNotContain("private slack discussion")
                .doesNotContain("raw payload")
                .doesNotContain("internal description")
                .doesNotContain("internal resolution notes")
                .doesNotContain("private-tag");
        }
        assertThat(body(get(PREFIX + "/incidents/" + demo.incidentId() + "/logs")))
            .contains("Connection is not available, request timed out after 30000ms");   // raw log line: included by decision
        assertThat(body(get(PREFIX + "/incidents/" + demo.incidentId() + "/timeline")))
            .doesNotContain("SLACK_ACTION_REQUESTED")
            .contains("ENGINEER_PAGED").contains("On-call engineer paged");
    }

    @Test
    void incidentViews_haveNoDescriptionField() throws Exception {
        JsonNode list   = objectMapper.readTree(body(get(PREFIX + "/incidents"))).path("data");
        JsonNode single = objectMapper.readTree(body(get(PREFIX + "/incidents/" + demo.incidentId()))).path("data");
        assertThat(list.isArray() && list.size() > 0).isTrue();
        list.forEach(incident -> assertThat(incident.has("description")).isFalse());
        assertThat(single.isObject()).isTrue();
        assertThat(single.has("description")).isFalse();
    }

    @Test
    void failedAnalysis_isNeverServed() throws Exception {
        withTenant(DEMO_TENANT, () -> aiAnalysisRepository.save(AIAnalysis.builder()
            .incidentId(demo.incidentId()).tenantId(DEMO_TENANT).confidenceScore(0).analysisVersion(2).evidenceCount(1)
            .status(AnalysisStatus.FAILED).errorMessage("Anthropic API call failed: CancellationException (read timeout)")
            .createdAt(Instant.now())
            .build()));
        TenantContext.clear();
        String body = mockMvc.perform(get(PREFIX + "/incidents/" + demo.incidentId() + "/analysis"))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Anthropic").doesNotContain("CancellationException");
    }

    // ── 10. Path tricks ────────────────────────────────────────────────────

    @Test
    void pathTricks_neverReachAnyHandlerAnonymously() throws Exception {
        String t = "?tenantId=" + DEMO_TENANT;
        List<String> tricks = List.of(
            PREFIX + "/../incidents" + t,
            PREFIX + "/..;/incidents" + t,
            PREFIX + "/%2e%2e/incidents" + t,
            PREFIX + "%2F..%2Fincidents" + t,
            PREFIX + ";x=y/../incidents" + t,
            PREFIX + "//../incidents" + t,
            PREFIX + "/incidents/%2e%2e/%2e%2e/%2e%2e/incidents" + t,
            "/API/V1/PUBLIC-DEMO/incidents",
            PREFIX + "/incidents/");
        for (String trick : tricks) {
            MvcResult result = mockMvc.perform(get(URI.create(trick))).andReturn();
            assertThat(result.getResponse().getStatus()).as(trick).isBetween(400, 499);
            assertThat(result.getResponse().getContentAsString()).as(trick)
                .doesNotContain(demo.incidentId().toString()).doesNotContain(other.incidentId().toString());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Map<RequestMappingInfo, HandlerMethod> demoRoutes() {
        Map<RequestMappingInfo, HandlerMethod> routes = new LinkedHashMap<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (info.getPatternValues().stream().anyMatch(p -> p.startsWith(PREFIX))) {
                routes.put(info, handler);
            }
        });
        return routes;
    }

    /** Every registered demo route, from the live handler mapping, with {id} filled in. */
    private List<String> concreteDemoPaths(UUID incidentId) {
        return demoRoutes().keySet().stream()
            .flatMap(info -> info.getPatternValues().stream())
            .map(p -> p.replace("{id}", incidentId.toString()))
            .sorted()
            .toList();
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> { names.add(e.getKey()); names.addAll(fieldNames(e.getValue())); });
        } else if (node.isArray()) {
            node.forEach(child -> names.addAll(fieldNames(child)));
        }
        return names;
    }

    private String createApiKey(UUID tenantId) {
        String key = withTenant(tenantId, () -> {
            Project project = projectRepository.save(Project.builder().tenantId(tenantId).name("demo").build());
            return apiKeyService.create(project.getId(), tenantId, new CreateApiKeyRequest("security test")).getPlaintext();
        });
        TenantContext.clear();
        return key;
    }

    /**
     * Row count + content hash of every table in the schema, read as the superuser the
     * migrations run as (it bypasses RLS, so every tenant's rows are included).
     */
    private String databaseFingerprint() throws Exception {
        try (Connection c = DriverManager.getConnection(environment.getProperty("spring.datasource.url"),
                 environment.getProperty("spring.flyway.user"), environment.getProperty("spring.flyway.password"));
             Statement s = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")) {
                while (rs.next()) tables.add(rs.getString(1));
            }
            StringBuilder fingerprint = new StringBuilder();
            for (String table : tables) {
                try (ResultSet rs = s.executeQuery("SELECT count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) "
                        + "FROM public.\"" + table + "\" x")) {
                    rs.next();
                    fingerprint.append(table).append('=').append(rs.getLong(1)).append(':').append(rs.getString(2)).append('\n');
                }
            }
            return fingerprint.toString();
        }
    }

    /**
     * Carries exactly the annotation every PublicDemoService method has, under the app's real
     * transaction manager, DataSource and JDBC driver -- so it proves what that annotation does
     * in Postgres, not just in Spring.
     */
    static class ReadOnlyProbe {
        private final JdbcTemplate jdbcTemplate;

        ReadOnlyProbe(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Transactional(readOnly = true)
        public void attemptWrite() {
            jdbcTemplate.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'read-only probe', 'STARTER')", UUID.randomUUID());
        }
    }
}
