package com.aetherops.analysis.service;

import com.aetherops.analysis.exception.AIProviderException;
import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.analysis.model.AIAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicAIProviderTest {

    private AnthropicAIProvider provider;
    private ObjectMapper        objectMapper;

    private static final String EVIDENCE_ID_1 = UUID.randomUUID().toString();
    private static final String EVIDENCE_ID_2 = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        // RestClient is not invoked in any of these tests — buildPrompt/parseResponse are tested directly
        provider = new AnthropicAIProvider("test-key", RestClient.create(), objectMapper);
    }

    // (1) Every evidence item ID from the context must appear in the built prompt
    @Test
    void buildPrompt_containsAllEvidenceIds() {
        String prompt = provider.buildPrompt(buildContext());
        assertThat(prompt).contains(EVIDENCE_ID_1);
        assertThat(prompt).contains(EVIDENCE_ID_2);
    }

    // (2) Core incident metadata must appear in the built prompt
    @Test
    void buildPrompt_containsIncidentMetadata() {
        String prompt = provider.buildPrompt(buildContext());
        assertThat(prompt).contains("payment-svc");
        assertThat(prompt).contains("HIGH");
        assertThat(prompt).contains("DB connection exhausted");
    }

    // (3) All fields parse correctly from a valid mock response
    @Test
    void parseResponse_mapsAllFieldsCorrectly() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
            "summary",               "The payment service is experiencing DB pool exhaustion.",
            "likelyCause",           "Connection pool exhausted [" + EVIDENCE_ID_1 + "].",
            "confidenceScore",       0.55,
            "evidenceRefs",          List.of(EVIDENCE_ID_1),
            "unknowns",              List.of("No metrics available", "No traces available"),
            "recommendedNextChecks", List.of("Check DB connection pool metrics")
        ));

        AIAnalysisResult result = provider.parseResponse(json, List.of(EVIDENCE_ID_1, EVIDENCE_ID_2));

        assertThat(result.getSummary()).isNotBlank();
        assertThat(result.getLikelyCause()).contains(EVIDENCE_ID_1);
        assertThat(result.getConfidenceScore()).isEqualTo(0.55);
        assertThat(result.getEvidenceRefs()).containsExactly(EVIDENCE_ID_1);
        assertThat(result.getUnknowns()).hasSize(2);
        assertThat(result.getRecommendedNextChecks()).hasSize(1);
        assertThat(result.getModel()).isEqualTo("claude-opus-5");
        assertThat(result.getPromptVersion()).isEqualTo("2.0");
    }

    // (4) A confidence score above 0.65 must be clamped to exactly 0.65
    @Test
    void parseResponse_capsConfidenceScoreAt065() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
            "summary",               "Summary.",
            "likelyCause",           "Cause.",
            "confidenceScore",       0.99,
            "evidenceRefs",          List.of(EVIDENCE_ID_1),
            "unknowns",              List.of(),
            "recommendedNextChecks", List.of()
        ));

        AIAnalysisResult result = provider.parseResponse(json, List.of(EVIDENCE_ID_1));

        assertThat(result.getConfidenceScore()).isEqualTo(0.65);
    }

    // (5) Evidence IDs not present in the context must be filtered out of evidenceRefs
    @Test
    void parseResponse_filtersHallucinatedEvidenceRefs() throws Exception {
        String hallucinatedId = UUID.randomUUID().toString();
        String json = objectMapper.writeValueAsString(Map.of(
            "summary",               "Summary.",
            "likelyCause",           "Cause.",
            "confidenceScore",       0.40,
            "evidenceRefs",          List.of(EVIDENCE_ID_1, hallucinatedId),
            "unknowns",              List.of(),
            "recommendedNextChecks", List.of()
        ));

        AIAnalysisResult result = provider.parseResponse(json, List.of(EVIDENCE_ID_1, EVIDENCE_ID_2));

        assertThat(result.getEvidenceRefs()).containsExactly(EVIDENCE_ID_1);
        assertThat(result.getEvidenceRefs()).doesNotContain(hallucinatedId);
    }

    // (6) Malformed JSON must throw AIProviderException
    @Test
    void parseResponse_throwsAIProviderExceptionOnMalformedJson() {
        assertThatThrownBy(() ->
            provider.parseResponse("not valid json {{{", List.of(EVIDENCE_ID_1))
        ).isInstanceOf(AIProviderException.class);
    }

    // (7) A leading thinking block must be skipped — the answer is the text block after it
    @Test
    void extractText_skipsThinkingBlock() throws Exception {
        var root = objectMapper.readTree("""
            {"stop_reason":"end_turn","content":[
              {"type":"thinking","thinking":"","signature":"sig"},
              {"type":"text","text":"{\\"summary\\":\\"ok\\"}"}
            ]}""");

        assertThat(provider.extractText(root)).isEqualTo("{\"summary\":\"ok\"}");
    }

    // (8) A classifier refusal must surface as AIProviderException, not an empty parse
    @Test
    void extractText_throwsOnRefusal() throws Exception {
        var root = objectMapper.readTree("""
            {"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber"},"content":[]}""");

        assertThatThrownBy(() -> provider.extractText(root))
            .isInstanceOf(AIProviderException.class)
            .hasMessageContaining("refusal");
    }

    // (9) A response with no text block (e.g. max_tokens hit mid-thinking) must throw
    @Test
    void extractText_throwsWhenNoTextBlock() throws Exception {
        var root = objectMapper.readTree("""
            {"stop_reason":"max_tokens","content":[{"type":"thinking","thinking":"","signature":"sig"}]}""");

        assertThatThrownBy(() -> provider.extractText(root))
            .isInstanceOf(AIProviderException.class)
            .hasMessageContaining("max_tokens");
    }

    // (10) A read timeout arrives as a message-less CancellationException — the error must still say what happened
    @Test
    void describe_namesTimeoutWhenMessageIsNull() {
        assertThat(AnthropicAIProvider.describe(new java.util.concurrent.CancellationException()))
            .isEqualTo("CancellationException (read timeout)");
    }

    // (11) Other exceptions keep their type and message
    @Test
    void describe_includesTypeAndMessage() {
        assertThat(AnthropicAIProvider.describe(new IllegalStateException("boom")))
            .isEqualTo("IllegalStateException: boom");
        assertThat(AnthropicAIProvider.describe(new IllegalStateException()))
            .isEqualTo("IllegalStateException");
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private AIAnalysisContext buildContext() {
        return AIAnalysisContext.builder()
            .incidentId(UUID.randomUUID())
            .tenantId(UUID.randomUUID())
            .incidentTitle("DB connection exhausted")
            .serviceId("payment-svc")
            .severity("HIGH")
            .status("OPEN")
            .firstSeen(Instant.now().minusSeconds(600))
            .lastSeen(Instant.now())
            .evidenceItems(List.of(
                AIAnalysisContext.EvidenceContext.builder()
                    .evidenceItemId(EVIDENCE_ID_1)
                    .type("LOG_EVENT")
                    .summary("Database connection pool exhausted after 30000ms")
                    .level("ERROR")
                    .timestamp(Instant.now().minusSeconds(300))
                    .build(),
                AIAnalysisContext.EvidenceContext.builder()
                    .evidenceItemId(EVIDENCE_ID_2)
                    .type("LOG_EVENT")
                    .summary("Database connection pool exhausted after 30000ms")
                    .level("ERROR")
                    .timestamp(Instant.now().minusSeconds(60))
                    .build()
            ))
            .build();
    }
}
