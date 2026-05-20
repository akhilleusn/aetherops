package com.aetherops.project.controller;

import com.aetherops.apikey.model.ApiKeyView;
import com.aetherops.apikey.model.CreateApiKeyRequest;
import com.aetherops.apikey.model.CreateApiKeyResponse;
import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.project.model.CreateProjectRequest;
import com.aetherops.project.model.Project;
import com.aetherops.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Manage projects and API keys")
public class ProjectController {

    private final ProjectService projectService;
    private final ApiKeyService  apiKeyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a project for a tenant")
    public ApiResponse<Project> create(@Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.success(projectService.create(request));
    }

    @GetMapping
    @Operation(summary = "List projects for a tenant")
    public ApiResponse<List<Project>> list(@RequestParam UUID tenantId) {
        return ApiResponse.success(projectService.listByTenant(tenantId));
    }

    @PostMapping("/{projectId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an API key under a project (plaintext returned once)")
    public ApiResponse<CreateApiKeyResponse> createKey(
        @PathVariable UUID projectId,
        @RequestParam UUID tenantId,
        @Valid @RequestBody(required = false) CreateApiKeyRequest request
    ) {
        return ApiResponse.success(apiKeyService.create(projectId, tenantId, request));
    }

    @GetMapping("/{projectId}/keys")
    @Operation(summary = "List API keys for a project (key prefix only, never plaintext or hash)")
    public ApiResponse<List<ApiKeyView>> listKeys(
        @PathVariable UUID projectId,
        @RequestParam UUID tenantId
    ) {
        return ApiResponse.success(apiKeyService.list(projectId, tenantId));
    }

    @DeleteMapping("/{projectId}/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke an API key")
    public void revokeKey(
        @PathVariable UUID projectId,
        @PathVariable UUID keyId,
        @RequestParam UUID tenantId
    ) {
        apiKeyService.revoke(projectId, tenantId, keyId);
    }
}
