package com.aetherops.tenant.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.tenant.model.*;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantService {

    private final TenantRepository tenantRepository;

    @Transactional
    public TenantResponse create(CreateTenantRequest request) {
        Tenant tenant = Tenant.builder()
            .name(request.getName())
            .plan(request.getPlan())
            .aiMonthlyBudgetCents(request.getAiMonthlyBudgetCents())
            .retentionDays(request.getRetentionDays())
            .build();

        Tenant saved = tenantRepository.save(tenant);
        log.info("Created tenant id={} name={} plan={}", saved.getId(), saved.getName(), saved.getPlan());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public TenantResponse findById(UUID id) {
        return tenantRepository.findById(id)
            .map(this::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<TenantResponse> findAll(Pageable pageable) {
        return tenantRepository.findAll(pageable).map(this::toResponse);
    }

    private TenantResponse toResponse(Tenant tenant) {
        return TenantResponse.builder()
            .id(tenant.getId())
            .name(tenant.getName())
            .plan(tenant.getPlan())
            .aiMonthlyBudgetCents(tenant.getAiMonthlyBudgetCents())
            .retentionDays(tenant.getRetentionDays())
            .createdAt(tenant.getCreatedAt())
            .updatedAt(tenant.getUpdatedAt())
            .build();
    }
}
