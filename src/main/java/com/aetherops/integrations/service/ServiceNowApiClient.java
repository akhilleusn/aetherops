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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Step H2 (see STEP_REPORT.md): the actual ServiceNow HTTP call, isolated in its own bean so
 * {@code @CircuitBreaker}/{@code @Bulkhead} take effect at all — see {@link JiraApiClient}'s
 * javadoc for why this can't just be a method on {@link ServiceNowTicketService} itself
 * (same-class self-invocation bypasses the Spring AOP proxy these annotations rely on).
 *
 * <p>Lets real call failures propagate as exceptions instead of swallowing them into a return
 * value, same reasoning as {@link JiraApiClient}.
 */
@Service
@Slf4j
public class ServiceNowApiClient {

    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ServiceNowApiClient(
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            @Value("${app.integrations.servicenow.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${app.integrations.servicenow.read-timeout-ms}") long readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.restClientBuilder = restClientBuilder;
        this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
    }

    @CircuitBreaker(name = "servicenow-ticket")
    @Bulkhead(name = "servicenow-ticket", type = Bulkhead.Type.THREADPOOL)
    public CompletableFuture<Optional<String>> createIncidentTicket(
            UUID tenantId, UUID incidentId, String shortDescription, String description,
            TenantTicketIntegration config, String decryptedToken) {

        // Re-validate at request time -- see SsrfGuard's javadoc. Throws SsrfBlockedException
        // on failure -- a permanent configuration problem, not transient provider unhealthiness,
        // so it's in this circuit breaker's ignore-exceptions list (see application.yml).
        SsrfGuard.validate(config.getBaseUrl());

        Map<String, Object> body = Map.of(
            "short_description", shortDescription,
            "description", description != null ? description : shortDescription,
            "urgency", "1"
        );

        ResponseEntity<String> response = SsrfGuard.disableRedirects(restClientBuilder, connectTimeout, readTimeout).build().post()
            .uri(config.getBaseUrl() + "/api/now/table/incident")
            .headers(h -> h.setBasicAuth(config.getUsername(), decryptedToken))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);

        if (response.getStatusCode().is3xxRedirection()) {
            log.warn("ServiceNow integration for tenantId={} returned a redirect — redirects are not followed, rejecting", tenantId);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        JsonNode json;
        try {
            json = objectMapper.readTree(response.getBody());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse ServiceNow response for incidentId=" + incidentId, e);
        }
        String sysId = json.path("result").path("sys_id").asText(null);
        if (sysId == null) {
            log.warn("ServiceNow ticket creation for incidentId={} returned no sys_id", incidentId);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        log.info("Created ServiceNow incident {} for incidentId={} tenantId={}", sysId, incidentId, tenantId);
        return CompletableFuture.completedFuture(Optional.of(sysId));
    }
}
