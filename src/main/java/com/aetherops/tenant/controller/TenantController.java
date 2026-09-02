package com.aetherops.tenant.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.TenantResponse;
import com.aetherops.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Tenants", description = "Manage customer/team accounts")
public class TenantController {

    private final TenantService tenantService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new tenant")
    public ApiResponse<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        return ApiResponse.success(tenantService.create(request));
    }

    @GetMapping
    @Operation(summary = "List all tenants (paginated)")
    public ApiResponse<Page<TenantResponse>> list(
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(tenantService.findAll(pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get tenant by ID")
    public ApiResponse<TenantResponse> getById(@PathVariable UUID id) {
        TenantContext.requireOwn(id);
        return ApiResponse.success(tenantService.findById(id));
    }
}
