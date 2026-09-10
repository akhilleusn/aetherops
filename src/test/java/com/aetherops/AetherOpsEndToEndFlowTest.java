package com.aetherops;

import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.postmortem.repository.PostmortemDraftRepository;
import com.aetherops.runbook.repository.RunbookSuggestionRepository;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aetherops.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end integration test covering the complete incident response lifecycle:
 *
 *   Tenant → Ingest events (with secrets) → Incident grouping → Evidence pack
 *   → AI analysis → Runbook suggestion → Postmortem draft
 *   → GET latest runbook → GET latest postmortem → Secret safety sweep
 *
 * This test proves the core product value in one shot and is the authoritative
 * verification of the demo flow documented in docs/demo/e2e-demo-curl.md.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AetherOpsEndToEndFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc        mockMvc;
    @Autowired ObjectMapper   objectMapper;
    @Autowired TenantRepository           tenantRepository;
    @Autowired LogEventRepository         logEventRepository;
    @Autowired IncidentRepository         incidentRepository;
    @Autowired EvidenceItemRepository     evidenceItemRepository;
    @Autowired AIAnalysisRepository       aiAnalysisRepository;
    @Autowired RunbookSuggestionRepository runbookSuggestionRepository;
    @Autowired PostmortemDraftRepository  postmortemDraftRepository;

    // Raw secrets that will be embedded in log messages — must be masked before storage.
    private static final String RAW_JWT     = "eyJhbGciOiJSUzI1NiJ9.e2RlbW99.demoSignature";
    private static final String RAW_API_KEY = "sk-prod-DEMO-8675309";
    private static final String SERVICE_ID  = "payment-svc";

    // All three events use the same base message so they normalise to the same fingerprint
    // after masking and group into a single incident with three evidence items.
    // The embedded secrets prove masking fires; the repeated pattern proves grouping fires.
    private static final String DEMO_MESSAGE_TEMPLATE =
        "Database connection pool exhausted — Bearer %s api_key=%s";

    @BeforeEach
    void setUp() {
        postmortemDraftRepository.deleteAll();
        runbookSuggestionRepository.deleteAll();
        aiAnalysisRepository.deleteAll();
        evidenceItemRepository.deleteAll();
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    @Test
    void fullIncidentLifecycle_endToEnd() throws Exception {

        // ── Step 1: Register a tenant ──────────────────────────────────────────
        CreateTenantRequest tenantRequest = new CreateTenantRequest("AcmeCorp Demo", 30);
        String tenantBody = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(tenantRequest))
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").isNotEmpty())
            .andExpect(jsonPath("$.data.name").value("AcmeCorp Demo"))
            .andReturn().getResponse().getContentAsString();

        UUID tenantId = UUID.fromString(
            objectMapper.readTree(tenantBody).at("/data/id").asText()
        );

        // ── Step 2: Ingest three log events — all embed raw secrets ───────────
        // Using the same message template for all three ensures the masking service
        // produces the same normalised fingerprint for each → all three group into
        // one incident. The Bearer token and api_key embedded in every message prove
        // masking fires reliably, not just on an edge-case event.
        String demoMessage = String.format(DEMO_MESSAGE_TEMPLATE, RAW_JWT, RAW_API_KEY);
        ingestEvent(tenantId, SERVICE_ID, LogLevel.ERROR, demoMessage);
        ingestEvent(tenantId, SERVICE_ID, LogLevel.ERROR, demoMessage);
        ingestEvent(tenantId, SERVICE_ID, LogLevel.ERROR, demoMessage);

        // ── Step 3: Verify incident was auto-created by grouping ──────────────
        String incidentsBody = mockMvc.perform(get("/api/v1/incidents")
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray())
            .andReturn().getResponse().getContentAsString();

        JsonNode incidents = objectMapper.readTree(incidentsBody).at("/data/content");
        assertThat(incidents.size())
            .as("exactly one incident should be grouped for payment-svc")
            .isEqualTo(1);

        UUID incidentId = UUID.fromString(incidents.get(0).at("/id").asText());
        assertThat(incidents.get(0).at("/serviceId").asText()).isEqualTo(SERVICE_ID);

        // ── Step 4: Fetch evidence pack — verify secret masking ───────────────
        String evidencePackBody = mockMvc.perform(get("/api/v1/incidents/{id}/evidence-pack", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.evidence").isArray())
            .andReturn().getResponse().getContentAsString();

        JsonNode evidenceArray = objectMapper.readTree(evidencePackBody).at("/data/evidence");
        assertThat(evidenceArray.size())
            .as("all 3 ingested events should appear as evidence items")
            .isEqualTo(3);

        // Collect real evidence item IDs and all summary text
        List<String> realEvidenceIds = new ArrayList<>();
        StringBuilder allSummaries   = new StringBuilder();
        for (JsonNode item : evidenceArray) {
            realEvidenceIds.add(item.at("/id").asText());
            allSummaries.append(item.at("/summary").asText("")).append(" ");
        }
        String summaryText = allSummaries.toString();

        // Secret masking must have fired: raw values must not appear in any summary
        assertThat(summaryText)
            .as("raw JWT must be masked in evidence summaries")
            .doesNotContain(RAW_JWT);
        assertThat(summaryText)
            .as("raw API key must be masked in evidence summaries")
            .doesNotContain(RAW_API_KEY);
        assertThat(summaryText)
            .as("[REDACTED] marker must appear in the evidence summaries")
            .contains("[REDACTED]");

        // ── Step 5: Generate AI analysis — verify evidence-grounded output ─────
        String analysisBody = mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").isNotEmpty())
            .andExpect(jsonPath("$.data.model").value("mock-v1"))
            .andReturn().getResponse().getContentAsString();

        JsonNode analysisData  = objectMapper.readTree(analysisBody).at("/data");
        JsonNode evidenceRefs  = analysisData.at("/evidenceRefs");
        assertThat(evidenceRefs.isArray()).isTrue();
        assertThat(evidenceRefs.size())
            .as("analysis must cite at least one evidence item")
            .isGreaterThan(0);
        for (JsonNode ref : evidenceRefs) {
            assertThat(realEvidenceIds)
                .as("every evidenceRef must be a real evidence item ID, not hallucinated")
                .contains(ref.asText());
        }
        assertThat(analysisBody).doesNotContain(RAW_JWT);
        assertThat(analysisBody).doesNotContain(RAW_API_KEY);

        // ── Step 6: Generate runbook suggestion — verify phased safe steps ─────
        String runbookBody = mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.steps").isArray())
            .andExpect(jsonPath("$.data.generatorVersion").value("mock-v1"))
            .andReturn().getResponse().getContentAsString();

        String stepsJson = objectMapper.readTree(runbookBody).at("/data/steps").toString().toUpperCase();
        assertThat(stepsJson).as("runbook must include TRIAGE steps").contains("TRIAGE");
        assertThat(stepsJson).as("runbook must include INVESTIGATE steps").contains("INVESTIGATE");
        assertThat(stepsJson).as("runbook must include VERIFY steps").contains("VERIFY");
        assertThat(runbookBody).doesNotContain(RAW_JWT);
        assertThat(runbookBody).doesNotContain(RAW_API_KEY);

        // ── Step 7: Generate postmortem draft — verify AI-enriched structure ───
        // Because /analyze ran in step 5, analysisEnriched must be true.
        String postmortemBody = mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.draftVersion").value(1))
            .andReturn().getResponse().getContentAsString();

        JsonNode postmortemData = objectMapper.readTree(postmortemBody).at("/data");
        assertThat(postmortemData.at("/incidentSummary").asText(""))
            .as("incidentSummary must be non-blank")
            .isNotBlank();
        assertThat(postmortemData.at("/timeline").isArray()).isTrue();
        assertThat(postmortemData.at("/timeline").size())
            .as("timeline must have at least one entry")
            .isGreaterThan(0);
        assertThat(postmortemData.at("/likelyCause").asText(""))
            .as("likelyCause must be non-blank")
            .isNotBlank();
        assertThat(postmortemData.at("/impact").asText(""))
            .as("impact must be non-blank")
            .isNotBlank();
        assertThat(postmortemData.at("/dataGaps").isArray()).isTrue();
        assertThat(postmortemData.at("/followUpActions").isArray()).isTrue();
        assertThat(postmortemData.at("/analysisEnriched").asBoolean())
            .as("postmortem should be enriched with AI analysis since /analyze ran first")
            .isTrue();

        // At least one timeline entry must cite a real evidence item ID
        boolean foundRealEvidenceRef = false;
        for (JsonNode entry : postmortemData.at("/timeline")) {
            if (realEvidenceIds.contains(entry.at("/evidenceRef").asText(""))) {
                foundRealEvidenceRef = true;
                break;
            }
        }
        assertThat(foundRealEvidenceRef)
            .as("postmortem timeline must cite at least one real evidence item ID")
            .isTrue();

        assertThat(postmortemBody).doesNotContain(RAW_JWT);
        assertThat(postmortemBody).doesNotContain(RAW_API_KEY);

        // ── Step 8: Fetch latest runbook ───────────────────────────────────────
        String latestRunbookBody = mockMvc.perform(get("/api/v1/incidents/{id}/runbook/latest", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.runbookVersion").value(1))
            .andExpect(jsonPath("$.data.incidentId").value(incidentId.toString()))
            .andExpect(jsonPath("$.data.tenantId").value(tenantId.toString()))
            .andReturn().getResponse().getContentAsString();

        // ── Step 9: Fetch latest postmortem ───────────────────────────────────
        String latestPostmortemBody = mockMvc.perform(get("/api/v1/incidents/{id}/postmortem/latest", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.draftVersion").value(1))
            .andExpect(jsonPath("$.data.incidentId").value(incidentId.toString()))
            .andExpect(jsonPath("$.data.tenantId").value(tenantId.toString()))
            .andReturn().getResponse().getContentAsString();

        // ── Step 10: Final secret safety sweep across all six response bodies ──
        String allBodies = evidencePackBody + analysisBody + runbookBody + postmortemBody
            + latestRunbookBody + latestPostmortemBody;

        assertThat(allBodies)
            .as("raw JWT must not appear in any endpoint response after masking")
            .doesNotContain(RAW_JWT);
        assertThat(allBodies)
            .as("raw API key must not appear in any endpoint response after masking")
            .doesNotContain(RAW_API_KEY);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void ingestEvent(UUID tenantId, String serviceId, LogLevel level, String message)
        throws Exception {
        LogEventRequest req = new LogEventRequest();
        req.setTenantId(tenantId);
        req.setServiceId(serviceId);
        req.setLevel(level);
        req.setMessage(message);
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated());
    }
}
