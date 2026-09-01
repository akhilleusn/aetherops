package com.aetherops.integrations.service;

import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.repository.TenantTicketIntegrationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Creates a ServiceNow incident-table record for an AetherOps incident on demand. See JiraTicketService for the shared design notes. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceNowTicketService {

    private final TenantTicketIntegrationRepository integrationRepository;
    private final TicketIntegrationTokenEncryptor tokenEncryptor;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    /** Returns empty when the tenant has no (enabled) ServiceNow config, or the API call fails — never throws. */
    public Optional<String> createIncidentTicket(UUID tenantId, UUID incidentId, String shortDescription, String description) {
        Optional<TenantTicketIntegration> configOpt = integrationRepository
            .findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)
            .filter(TenantTicketIntegration::isEnabled);
        if (configOpt.isEmpty()) {
            log.debug("No enabled ServiceNow integration for tenantId={}, skipping ticket creation", tenantId);
            return Optional.empty();
        }
        TenantTicketIntegration config = configOpt.get();

        try {
            String token = tokenEncryptor.decrypt(config.getEncryptedApiToken());
            Map<String, Object> body = Map.of(
                "short_description", shortDescription,
                "description", description != null ? description : shortDescription,
                "urgency", "1"
            );

            String responseBody = restClientBuilder.build().post()
                .uri(config.getBaseUrl() + "/api/now/table/incident")
                .headers(h -> h.setBasicAuth(config.getUsername(), token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

            JsonNode json = objectMapper.readTree(responseBody);
            String sysId = json.path("result").path("sys_id").asText(null);
            if (sysId == null) {
                log.warn("ServiceNow ticket creation for incidentId={} returned no sys_id", incidentId);
                return Optional.empty();
            }
            log.info("Created ServiceNow incident {} for incidentId={} tenantId={}", sysId, incidentId, tenantId);
            return Optional.of(sysId);
        } catch (Exception e) {
            log.warn("Failed to create ServiceNow ticket for incidentId={} tenantId={}", incidentId, tenantId, e);
            return Optional.empty();
        }
    }
}
