package com.aetherops.analysis.service;

import com.aetherops.analysis.exception.AIProviderException;
import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.analysis.model.AIAnalysisResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@RequiredArgsConstructor
@Slf4j
public class AnthropicAIProvider implements AIProvider {

    private static final String MODEL          = "claude-sonnet-4-20250514";
    private static final String PROMPT_VERSION = "2.0";
    private static final String API_URL        = "https://api.anthropic.com/v1/messages";
    private static final double CONFIDENCE_CAP = 0.65;

    private final String       apiKey;
    private final RestClient   restClient;
    private final ObjectMapper objectMapper;

    @Override
    public AIAnalysisResult analyze(AIAnalysisContext context) {
        String prompt   = buildPrompt(context);
        String rawText  = callApi(prompt);
        List<String> contextIds = context.getEvidenceItems().stream()
            .map(AIAnalysisContext.EvidenceContext::getEvidenceItemId)
            .toList();
        return parseResponse(rawText, contextIds);
    }

    // package-private for unit testing
    String buildPrompt(AIAnalysisContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an SRE analysis assistant. Analyze the following production incident ");
        sb.append("and respond with ONLY valid JSON — no explanation, no markdown, no preamble.\n\n");

        sb.append("Incident\n--------\n");
        sb.append("Title:      ").append(context.getIncidentTitle()).append('\n');
        sb.append("Service:    ").append(context.getServiceId()).append('\n');
        sb.append("Severity:   ").append(context.getSeverity()).append('\n');
        sb.append("Status:     ").append(context.getStatus()).append('\n');
        sb.append("First seen: ").append(context.getFirstSeen()).append('\n');
        sb.append("Last seen:  ").append(context.getLastSeen()).append('\n');

        List<AIAnalysisContext.EvidenceContext> items = context.getEvidenceItems();
        sb.append("\nEvidence (").append(items.size()).append(" item").append(items.size() == 1 ? "" : "s").append(")\n");
        sb.append("--------------------\n");
        for (AIAnalysisContext.EvidenceContext item : items) {
            sb.append('[').append(item.getEvidenceItemId()).append("] ");
            sb.append(item.getLevel()).append(" at ").append(item.getTimestamp()).append('\n');
            sb.append(item.getSummary()).append('\n');
            sb.append('\n');
        }

        sb.append("Respond using exactly this JSON structure:\n");
        sb.append("{\n");
        sb.append("  \"summary\": \"2-3 sentence summary of what happened\",\n");
        sb.append("  \"likelyCause\": \"most probable root cause; must cite at least one evidence ID in brackets\",\n");
        sb.append("  \"confidenceScore\": 0.0,\n");
        sb.append("  \"evidenceRefs\": [\"<evidenceItemId>\"],\n");
        sb.append("  \"unknowns\": [\"what you cannot determine from available evidence alone\"],\n");
        sb.append("  \"recommendedNextChecks\": [\"safe, read-only investigative step\"]\n");
        sb.append("}\n\n");

        sb.append("Rules (strictly enforced):\n");
        sb.append("- confidenceScore: decimal 0.0-1.0. Cap at 0.65 — you have only log events; no metrics, distributed traces, or deployment history are available.\n");
        sb.append("- evidenceRefs: include only IDs from the evidence items listed above.\n");
        sb.append("- recommendedNextChecks: read-only investigative actions only. Never suggest: delete, drop, restart, kill, truncate, remove, wipe, purge, shutdown, flush, or any destructive action.\n");
        sb.append("- unknowns: clearly state what you cannot assess without metrics, traces, or deploy data.\n");
        sb.append("- Respond with the JSON object only. No other text.");

        return sb.toString();
    }

    // package-private for unit testing
    AIAnalysisResult parseResponse(String responseText, List<String> contextIds) {
        Set<String> validIds = Set.copyOf(contextIds);
        try {
            JsonNode json = objectMapper.readTree(responseText);

            double rawConfidence = json.path("confidenceScore").asDouble(0.35);
            double confidence    = Math.min(rawConfidence, CONFIDENCE_CAP);

            List<String> refs = toStringList(json.path("evidenceRefs"))
                .stream()
                .filter(validIds::contains)
                .toList();

            return AIAnalysisResult.builder()
                .summary(json.path("summary").asText(""))
                .likelyCause(json.path("likelyCause").asText(""))
                .confidenceScore(confidence)
                .evidenceRefs(refs)
                .unknowns(toStringList(json.path("unknowns")))
                .recommendedNextChecks(toStringList(json.path("recommendedNextChecks")))
                .model(MODEL)
                .promptVersion(PROMPT_VERSION)
                .build();

        } catch (JsonProcessingException e) {
            throw new AIProviderException("Failed to parse LLM response as JSON: " + e.getMessage(), e);
        }
    }

    private String callApi(String prompt) {
        Map<String, Object> body = Map.of(
            "model",      MODEL,
            "max_tokens", 1000,
            "messages",   List.of(Map.of("role", "user", "content", prompt))
        );

        try {
            String raw = restClient.post()
                .uri(API_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

            JsonNode root = objectMapper.readTree(raw);
            return root.at("/content/0/text").asText();

        } catch (JsonProcessingException e) {
            throw new AIProviderException("Failed to parse Anthropic API response: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new AIProviderException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    private List<String> toStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(n -> result.add(n.asText()));
        return result;
    }
}
