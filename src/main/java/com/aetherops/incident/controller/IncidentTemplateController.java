package com.aetherops.incident.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.incident.model.CreateIncidentTemplateRequest;
import com.aetherops.incident.model.IncidentTemplateResponse;
import com.aetherops.incident.service.IncidentTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incident-templates")
@RequiredArgsConstructor
@Tag(name = "Incident Templates", description = "Reusable defaults for common incident types")
public class IncidentTemplateController {

    private final IncidentTemplateService templateService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a tenant-specific incident template")
    public ApiResponse<IncidentTemplateResponse> create(
        @RequestParam UUID tenantId,
        @Valid @RequestBody CreateIncidentTemplateRequest request
    ) {
        return ApiResponse.success(templateService.create(tenantId, request));
    }

    @GetMapping
    @Operation(summary = "List templates visible to the tenant (global defaults + tenant-specific)")
    public ApiResponse<List<IncidentTemplateResponse>> list(@RequestParam UUID tenantId) {
        return ApiResponse.success(templateService.list(tenantId));
    }
}
