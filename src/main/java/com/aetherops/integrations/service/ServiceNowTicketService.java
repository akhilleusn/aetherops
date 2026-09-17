package com.aetherops.integrations.service;

import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.repository.TenantTicketIntegrationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Creates a ServiceNow incident-table record for an AetherOps incident on demand. See
 * JiraTicketService for the shared design notes — this mirrors it exactly (tenant-config lookup
 * here, the actual HTTP call in {@link ServiceNowApiClient} behind its own circuit breaker and
 * {@code THREADPOOL} bulkhead, bounded-wait rather than true async so the response contract is
 * unchanged, no retry since ticket creation is a non-idempotent POST).
 */
@Service
@Slf4j
public class ServiceNowTicketService {

    private final TenantTicketIntegrationRepository integrationRepository;
    private final TicketIntegrationTokenEncryptor tokenEncryptor;
    private final ServiceNowApiClient apiClient;
    private final long callTimeoutMs;

    public ServiceNowTicketService(
            TenantTicketIntegrationRepository integrationRepository,
            TicketIntegrationTokenEncryptor tokenEncryptor,
            ServiceNowApiClient apiClient,
            @Value("${app.integrations.servicenow.call-timeout-ms}") long callTimeoutMs) {
        this.integrationRepository = integrationRepository;
        this.tokenEncryptor = tokenEncryptor;
        this.apiClient = apiClient;
        this.callTimeoutMs = callTimeoutMs;
    }

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
            return apiClient.createIncidentTicket(tenantId, incidentId, shortDescription, description, config, token)
                .get(callTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Failed to create ServiceNow ticket for incidentId={} tenantId={}", incidentId, tenantId, e);
            return Optional.empty();
        }
    }
}
