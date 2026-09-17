package com.aetherops.integrations.service;

import com.aetherops.common.security.SsrfGuard;
import com.aetherops.integrations.model.TenantTicketIntegration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Step H2 (see STEP_REPORT.md): the actual Jira HTTP call, isolated in its own bean so
 * {@code @CircuitBreaker}/{@code @Bulkhead} take effect at all. Deliberately a separate bean
 * from {@link JiraTicketService} — Spring AOP proxies (which is what these annotations rely on)
 * do not intercept same-class self-invocation, so a call from {@code JiraTicketService} into a
 * method on itself would silently skip both the circuit breaker and the bulkhead. Same
 * reasoning this codebase already applies to {@code @Async} (see the Slack package's paging
 * response service history in STEP_REPORT.md).
 *
 * <p>Lets real call failures (timeout, connection refused, SSRF-blocked baseUrl) propagate as
 * exceptions instead of swallowing them into a return value — a circuit breaker that never sees
 * a failure can never trip. {@code JiraTicketService} is the layer that catches, logs, and
 * converts to {@code Optional.empty()} for the caller. "Not configured for this tenant" is
 * handled entirely in {@code JiraTicketService} before this bean is ever called, so it never
 * counts as bulkhead/circuit-breaker traffic.
 */
@Service
@Slf4j
public class JiraApiClient {

    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public JiraApiClient(
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            @Value("${app.integrations.jira.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${app.integrations.jira.read-timeout-ms}") long readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.restClientBuilder = restClientBuilder;
        this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
    }

    @CircuitBreaker(name = "jira-ticket")
    @Bulkhead(name = "jira-ticket", type = Bulkhead.Type.THREADPOOL)
    public CompletableFuture<Optional<String>> createIssue(
            UUID tenantId, UUID incidentId, String summary, String description,
            TenantTicketIntegration config, String decryptedToken) {

        // Re-validate at request time, not just at configuration time -- DNS for an
        // already-accepted baseUrl can be repointed after it was configured. See
        // SsrfGuard's javadoc. Throws SsrfBlockedException on failure -- a permanent
        // configuration problem, not transient provider unhealthiness, so it's in this
        // circuit breaker's ignore-exceptions list (see application.yml).
        SsrfGuard.validate(config.getBaseUrl());

        Map<String, Object> descriptionAdf = Map.of(
            "type", "doc", "version", 1,
            "content", List.of(Map.of(
                "type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", description != null ? description : summary))
            ))
        );
        Map<String, Object> body = Map.of("fields", Map.of(
            "project", Map.of("key", config.getProjectKey()),
            "summary", summary,
            "description", descriptionAdf,
            "issuetype", Map.of("name", "Bug")
        ));

        ResponseEntity<String> response = SsrfGuard.disableRedirects(restClientBuilder, connectTimeout, readTimeout).build().post()
            .uri(config.getBaseUrl() + "/rest/api/3/issue")
            .headers(h -> h.setBasicAuth(config.getUsername(), decryptedToken))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);

        if (response.getStatusCode().is3xxRedirection()) {
            log.warn("Jira integration for tenantId={} returned a redirect — redirects are not followed, rejecting", tenantId);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        JsonNode json;
        try {
            json = objectMapper.readTree(response.getBody());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse Jira response for incidentId=" + incidentId, e);
        }
        String key = json.path("key").asText(null);
        if (key == null) {
            log.warn("Jira issue creation for incidentId={} returned no key", incidentId);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        log.info("Created Jira issue {} for incidentId={} tenantId={}", key, incidentId, tenantId);
        return CompletableFuture.completedFuture(Optional.of(key));
    }
}
