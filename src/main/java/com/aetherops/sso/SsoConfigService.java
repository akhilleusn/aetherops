package com.aetherops.sso;

import com.aetherops.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SsoConfigService {

    private final TenantSsoConfigRepository ssoConfigRepository;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.sso.sp-entity-id:aetherops}")
    private String spEntityId;

    @Transactional
    public TenantSsoConfigResponse configure(UUID tenantId, ConfigureSsoRequest request) {
        TenantSsoConfig config = ssoConfigRepository.findByTenantId(tenantId)
            .orElseGet(() -> TenantSsoConfig.builder().tenantId(tenantId).build());

        config.setIdpEntityId(request.getIdpEntityId());
        config.setIdpSsoUrl(request.getIdpSsoUrl());
        config.setIdpCertificate(request.getIdpCertificate());
        config.setEnabled(request.isEnabled());

        TenantSsoConfig saved = ssoConfigRepository.save(config);
        log.info("Configured SAML SSO for tenantId={} enabled={}", tenantId, saved.isEnabled());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public TenantSsoConfigResponse get(UUID tenantId) {
        TenantSsoConfig config = ssoConfigRepository.findByTenantId(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("No SSO configuration for tenant: " + tenantId));
        return toResponse(config);
    }

    private TenantSsoConfigResponse toResponse(TenantSsoConfig config) {
        return TenantSsoConfigResponse.builder()
            .tenantId(config.getTenantId())
            .enabled(config.isEnabled())
            .idpEntityId(config.getIdpEntityId())
            .idpSsoUrl(config.getIdpSsoUrl())
            .acsUrl(baseUrl + "/login/saml2/sso/" + config.getTenantId())
            .spEntityId(spEntityId)
            .updatedAt(config.getUpdatedAt())
            .build();
    }
}
