package com.aetherops.ratelimit;

import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.aetherops.AbstractIntegrationTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.rate-limit.requests-per-window=3",
    "app.rate-limit.window-seconds=60"
})
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc         mockMvc;
    @Autowired ObjectMapper    objectMapper;
    @Autowired TenantRepository tenantRepository;
    @Autowired LogEventRepository logEventRepository;

    @BeforeEach
    void setUp() {
        logEventRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    // ── header presence and values ────────────────────────────────────────

    @Test
    void firstRequest_returns201WithRateLimitHeaders() throws Exception {
        UUID tenantId = freshTenant();

        ResultActions result = ingest(tenantId, null);
        result.andExpect(status().isCreated())
            .andExpect(header().string("X-RateLimit-Limit",     "3"))
            .andExpect(header().string("X-RateLimit-Remaining", "2"))
            .andExpect(header().exists("X-RateLimit-Reset"));

        long resetAt = Long.parseLong(
            result.andReturn().getResponse().getHeader("X-RateLimit-Reset"));
        assertThat(resetAt).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void remainingHeader_decreasesWithEachRequest() throws Exception {
        UUID tenantId = freshTenant();

        assertRemainingIs(ingest(tenantId, null), 2);
        assertRemainingIs(ingest(tenantId, null), 1);
        assertRemainingIs(ingest(tenantId, null), 0);
    }

    @Test
    void atLimit_thirdRequest_stillReturns201() throws Exception {
        UUID tenantId = freshTenant();
        ingest(tenantId, null);
        ingest(tenantId, null);
        ingest(tenantId, null).andExpect(status().isCreated());
    }

    // ── 429 behavior ──────────────────────────────────────────────────────

    @Test
    void overLimit_returns429() throws Exception {
        UUID tenantId = freshTenant();
        for (int i = 0; i < 3; i++) ingest(tenantId, null);

        ingest(tenantId, null)
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.error").value("Too Many Requests"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.path").value("/api/v1/events"))
            .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void overLimit_rateLimitHeadersPresent() throws Exception {
        UUID tenantId = freshTenant();
        for (int i = 0; i < 3; i++) ingest(tenantId, null);

        ingest(tenantId, null)
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("X-RateLimit-Limit",     "3"))
            .andExpect(header().string("X-RateLimit-Remaining", "0"))
            .andExpect(header().exists("X-RateLimit-Reset"));
    }

    // ── isolation ─────────────────────────────────────────────────────────

    @Test
    void differentTenants_trackedIndependently() throws Exception {
        UUID tenantA = freshTenant();
        UUID tenantB = freshTenant();

        for (int i = 0; i < 3; i++) ingest(tenantA, null);
        ingest(tenantA, null).andExpect(status().isTooManyRequests());

        ingest(tenantB, null).andExpect(status().isCreated());
    }

    @Test
    void noApiKey_limitsByTenantId() throws Exception {
        UUID tenantId = freshTenant();

        for (int i = 0; i < 3; i++) {
            ingest(tenantId, null).andExpect(status().isCreated());
        }
        ingest(tenantId, null).andExpect(status().isTooManyRequests());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private UUID freshTenant() {
        return tenantRepository.save(Tenant.builder()
            .name("RL Test " + UUID.randomUUID())
            .plan(Plan.STARTER)
            .aiMonthlyBudgetCents(0)
            .retentionDays(30)
            .build()).getId();
    }

    private ResultActions ingest(UUID tenantId, String apiKeyHeader) throws Exception {
        LogEventRequest req = new LogEventRequest();
        req.setTenantId(tenantId);
        req.setServiceId("test-svc");
        req.setLevel(LogLevel.INFO);
        req.setMessage("rate limit test");
        req.setOccurredAt(Instant.now());

        var builder = post("/api/v1/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(req));
        if (apiKeyHeader != null) builder.header("X-Api-Key", apiKeyHeader);
        return mockMvc.perform(builder);
    }

    private void assertRemainingIs(ResultActions result, int expected) throws Exception {
        result.andExpect(header().string("X-RateLimit-Remaining", String.valueOf(expected)));
    }
}
