package com.aetherops.project.controller;

import com.aetherops.apikey.model.ApiKeyView;
import com.aetherops.apikey.model.CreateApiKeyRequest;
import com.aetherops.apikey.model.CreateApiKeyResponse;
import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.project.model.CreateProjectRequest;
import com.aetherops.project.model.Project;
import com.aetherops.project.service.ProjectService;
import com.aetherops.service.model.CreateServiceRequest;
import com.aetherops.service.model.Service;
import com.aetherops.service.service.ServiceRegistryService;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Manage projects and API keys")
public class ProjectController {

    private final ProjectService         projectService;
    private final ApiKeyService          apiKeyService;
    private final ServiceRegistryService serviceRegistryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Create a project for a tenant")
    public ApiResponse<Project> create(@Valid @RequestBody CreateProjectRequest request) {
        TenantContext.requireOwn(request.getTenantId());
        return ApiResponse.success(projectService.create(request));
    }

    @GetMapping
    @Operation(summary = "List projects for a tenant")
    public ApiResponse<List<Project>> list(@RequestParam UUID tenantId) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(projectService.listByTenant(tenantId));
    }

    @PostMapping("/{projectId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Create an API key under a project (plaintext returned once)")
    public ApiResponse<CreateApiKeyResponse> createKey(
        @PathVariable UUID projectId,
        @RequestParam UUID tenantId,
        @Valid @RequestBody(required = false) CreateApiKeyRequest request
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(apiKeyService.create(projectId, tenantId, request));
    }

    @GetMapping("/{projectId}/keys")
    @Operation(summary = "List API keys for a project (key prefix only, never plaintext or hash)")
    public ApiResponse<List<ApiKeyView>> listKeys(
        @PathVariable UUID projectId,
        @RequestParam UUID tenantId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(apiKeyService.list(projectId, tenantId));
    }

    @DeleteMapping("/{projectId}/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Revoke an API key")
    public void revokeKey(
        @PathVariable UUID projectId,
        @PathVariable UUID keyId,
        @RequestParam UUID tenantId
    ) {
        TenantContext.requireOwn(tenantId);
        apiKeyService.revoke(projectId, tenantId, keyId);
    }

    @PostMapping("/{projectId}/services")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Register a service under a project")
    public ApiResponse<Service> createService(
        @PathVariable UUID projectId,
        @RequestParam UUID tenantId,
        @Valid @RequestBody CreateServiceRequest request
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(serviceRegistryService.create(projectId, tenantId, request));
    }

    @GetMapping("/{projectId}/services")
    @Operation(summary = "List services registered under a project")
    public ApiResponse<List<Service>> listServices(
        @PathVariable UUID projectId,
        @RequestParam UUID tenantId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(serviceRegistryService.listByProject(projectId, tenantId));
    }
}
