package com.aetherops.incident.controller;

import com.aetherops.analysis.model.AIAnalysisResponse;
import com.aetherops.analysis.service.AIAnalysisService;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.evidence.service.EvidencePackService;
import com.aetherops.incident.model.*;
import com.aetherops.incident.service.IncidentService;
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
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
@Tag(name = "Incidents", description = "Manage production incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final EvidencePackService evidencePackService;
    private final AIAnalysisService aiAnalysisService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Manually create an incident")
    public ApiResponse<IncidentResponse> create(@Valid @RequestBody CreateIncidentRequest request) {
        return ApiResponse.success(incidentService.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get incident by ID")
    public ApiResponse<IncidentResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(incidentService.findById(id));
    }

    @GetMapping
    @Operation(summary = "List incidents for a tenant, with optional filters")
    public ApiResponse<Page<IncidentResponse>> list(
        @RequestParam UUID tenantId,
        @RequestParam(required = false) IncidentStatus status,
        @RequestParam(required = false) String serviceId,
        @RequestParam(required = false) IncidentSeverity severity,
        @PageableDefault(size = 20, sort = "lastSeenAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(incidentService.findAll(tenantId, status, serviceId, severity, pageable));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update incident status, severity, title, or description")
    public ApiResponse<IncidentResponse> update(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateIncidentRequest request
    ) {
        return ApiResponse.success(incidentService.update(id, request));
    }

    @GetMapping("/{id}/evidence-pack")
    @Operation(summary = "Get the evidence pack for an incident")
    public ApiResponse<IncidentEvidencePackResponse> getEvidencePack(
        @PathVariable UUID id,
        @RequestParam UUID tenantId
    ) {
        return ApiResponse.success(evidencePackService.getEvidencePack(id, tenantId));
    }

    @PostMapping("/{id}/analyze")
    @Operation(summary = "Run AI analysis on the incident's evidence pack. Each call creates a new analysis.")
    public ApiResponse<AIAnalysisResponse> analyze(
        @PathVariable UUID id,
        @RequestParam UUID tenantId
    ) {
        return ApiResponse.success(aiAnalysisService.analyze(id, tenantId));
    }
}
