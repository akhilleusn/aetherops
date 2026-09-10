package com.aetherops.integrations.service;

import com.aetherops.common.security.SsrfGuard;
import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.repository.TenantTicketIntegrationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates a Jira issue for an incident on demand. Never wired automatically into
 * incident-creation — that would require editing the incident-service files this package
 * intentionally leaves alone — callers (e.g. TicketController) trigger it explicitly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JiraTicketService {

    private final TenantTicketIntegrationRepository integrationRepository;
    private final TicketIntegrationTokenEncryptor tokenEncryptor;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    /** Returns empty when the tenant has no (enabled) Jira config, or the API call fails — never throws. */
    public Optional<String> createIssue(UUID tenantId, UUID incidentId, String summary, String description) {
        Optional<TenantTicketIntegration> configOpt = integrationRepository
            .findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)
            .filter(TenantTicketIntegration::isEnabled);
        if (configOpt.isEmpty()) {
            log.debug("No enabled Jira integration for tenantId={}, skipping issue creation", tenantId);
            return Optional.empty();
        }
        TenantTicketIntegration config = configOpt.get();

        try {
            // Re-validate at request time, not just at configuration time -- DNS for an
            // already-accepted baseUrl can be repointed after it was configured. See
            // SsrfGuard's javadoc.
            SsrfGuard.validate(config.getBaseUrl());

            String token = tokenEncryptor.decrypt(config.getEncryptedApiToken());
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

            ResponseEntity<String> response = SsrfGuard.disableRedirects(restClientBuilder).build().post()
                .uri(config.getBaseUrl() + "/rest/api/3/issue")
                .headers(h -> h.setBasicAuth(config.getUsername(), token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);

            if (response.getStatusCode().is3xxRedirection()) {
                log.warn("Jira integration for tenantId={} returned a redirect — redirects are not followed, rejecting", tenantId);
                return Optional.empty();
            }

            JsonNode json = objectMapper.readTree(response.getBody());
            String key = json.path("key").asText(null);
            if (key == null) {
                log.warn("Jira issue creation for incidentId={} returned no key", incidentId);
                return Optional.empty();
            }
            log.info("Created Jira issue {} for incidentId={} tenantId={}", key, incidentId, tenantId);
            return Optional.of(key);
        } catch (Exception e) {
            log.warn("Failed to create Jira issue for incidentId={} tenantId={}", incidentId, tenantId, e);
            return Optional.empty();
        }
    }
}
