package com.aetherops.integrations.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.integrations.model.ConfigureTicketIntegrationRequest;
import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketIntegrationResponse;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.repository.TenantTicketIntegrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketIntegrationConfigService {

    private final TenantTicketIntegrationRepository repository;
    private final TicketIntegrationTokenEncryptor encryptor;

    @Transactional
    public TicketIntegrationResponse configure(UUID tenantId, ConfigureTicketIntegrationRequest request) {
        TenantTicketIntegration integration = repository.findByTenantIdAndProvider(tenantId, request.getProvider())
            .orElseGet(() -> TenantTicketIntegration.builder()
                .tenantId(tenantId).provider(request.getProvider()).build());

        integration.setBaseUrl(request.getBaseUrl());
        integration.setProjectKey(request.getProjectKey());
        integration.setUsername(request.getUsername());
        integration.setEncryptedApiToken(encryptor.encrypt(request.getApiToken()));
        integration.setEnabled(true);

        TenantTicketIntegration saved = repository.save(integration);
        log.info("Configured {} ticket integration for tenantId={}", request.getProvider(), tenantId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public TicketIntegrationResponse get(UUID tenantId, TicketProvider provider) {
        TenantTicketIntegration integration = repository.findByTenantIdAndProvider(tenantId, provider)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No " + provider + " ticket integration configured for tenant: " + tenantId));
        return toResponse(integration);
    }

    @Transactional
    public void delete(UUID tenantId, TicketProvider provider) {
        repository.findByTenantIdAndProvider(tenantId, provider).ifPresent(repository::delete);
    }

    private TicketIntegrationResponse toResponse(TenantTicketIntegration integration) {
        return TicketIntegrationResponse.builder()
            .tenantId(integration.getTenantId())
            .provider(integration.getProvider())
            .baseUrl(integration.getBaseUrl())
            .projectKey(integration.getProjectKey())
            .username(integration.getUsername())
            .enabled(integration.isEnabled())
            .updatedAt(integration.getUpdatedAt())
            .build();
    }
}
