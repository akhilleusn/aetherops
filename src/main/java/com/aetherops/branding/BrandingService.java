package com.aetherops.branding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrandingService {

    private final TenantBrandingRepository brandingRepository;

    @Transactional
    public BrandingResponse configure(UUID tenantId, BrandingRequest request) {
        TenantBranding branding = brandingRepository.findByTenantId(tenantId)
            .orElseGet(() -> TenantBranding.builder().tenantId(tenantId).build());

        branding.setCompanyName(request.getCompanyName());
        branding.setLogoUrl(request.getLogoUrl());
        branding.setPrimaryColor(request.getPrimaryColor());
        branding.setSecondaryColor(request.getSecondaryColor());

        TenantBranding saved = brandingRepository.save(branding);
        log.info("Configured branding for tenantId={}", tenantId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Optional<BrandingResponse> get(UUID tenantId) {
        return brandingRepository.findByTenantId(tenantId).map(this::toResponse);
    }

    private BrandingResponse toResponse(TenantBranding branding) {
        return BrandingResponse.builder()
            .tenantId(branding.getTenantId())
            .companyName(branding.getCompanyName())
            .logoUrl(branding.getLogoUrl())
            .primaryColor(branding.getPrimaryColor())
            .secondaryColor(branding.getSecondaryColor())
            .updatedAt(branding.getUpdatedAt())
            .build();
    }
}
