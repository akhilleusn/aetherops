package com.aetherops.incident.controller;

import com.aetherops.analysis.model.AIAnalysisResponse;
import com.aetherops.analysis.service.AIAnalysisService;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.evidence.service.EvidencePackService;
import com.aetherops.incident.model.*;
import com.aetherops.incident.service.IncidentService;
import com.aetherops.oncall.IncidentTimelineResponse;
import com.aetherops.oncall.IncidentTimelineService;
import com.aetherops.postmortem.model.PostmortemDraftResponse;
import com.aetherops.postmortem.service.PostmortemService;
import com.aetherops.runbook.model.RunbookSuggestionResponse;
import com.aetherops.runbook.service.RunbookService;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
@Tag(name = "Incidents", description = "Manage production incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final EvidencePackService evidencePackService;
    private final AIAnalysisService aiAnalysisService;
    private final RunbookService runbookService;
    private final PostmortemService postmortemService;
    private final IncidentTimelineService incidentTimelineService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Manually create an incident")
    public ApiResponse<IncidentResponse> create(
            @Valid @RequestBody CreateIncidentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        // Phase 2 remediation, finding 5 (see STEP_REPORT.md) -- CreateIncidentRequest.tenantId
        // is client-settable; IncidentCommandController's parallel CQRS endpoint already does
        // this same check for the identical kind of resource. Was missing here.
        TenantContext.requireOwn(request.getTenantId());
        return ApiResponse.success(incidentService.create(request, idempotencyKey));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get incident by ID")
    public ApiResponse<IncidentResponse> getById(
        @PathVariable UUID id,
        @RequestParam UUID tenantId
    ) {
        // Defense in depth: this only validates the caller's OWN claimed tenant matches
        // their JWT — it can't and doesn't prove `id` belongs to that tenant (a caller
        // could pass their own real tenantId alongside someone else's incident id). The
        // primary control is IncidentService.findById filtering by TenantContext.get() in
        // the query itself; this check just fails fast on an obviously-wrong tenantId.
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(incidentService.findById(id));
    }

    @GetMapping
    @Operation(summary = "List incidents for a tenant, with optional filters")
    public ApiResponse<Page<IncidentResponse>> list(
        @RequestParam UUID tenantId,
        @RequestParam(required = false) IncidentStatus status,
        @RequestParam(required = false) String serviceId,
        @RequestParam(required = false) IncidentSeverity severity,
        @RequestParam(required = false) UUID projectId,
        @PageableDefault(size = 20, sort = "lastSeenAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(incidentService.findAll(tenantId, status, serviceId, severity, projectId, pageable));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Update incident status, severity, title, or description")
    public ApiResponse<IncidentResponse> update(
        @PathVariable UUID id,
        @RequestParam UUID tenantId,
        @Valid @RequestBody UpdateIncidentRequest request
    ) {
        // See getById — controller-level check is defense in depth, not the primary
        // control. IncidentService.update filters by TenantContext.get() in the query.
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(incidentService.update(id, request));
    }

    @GetMapping("/{id}/evidence-pack")
    @Operation(summary = "Get the evidence pack for an incident")
    public ApiResponse<IncidentEvidencePackResponse> getEvidencePack(
        @PathVariable UUID id,
        @RequestParam UUID tenantId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(evidencePackService.getEvidencePack(id, tenantId));
    }

    @PostMapping("/{id}/analyze")
    @Operation(summary = "Run AI analysis on the incident's evidence pack. Each call creates a new analysis.")
    public ApiResponse<AIAnalysisResponse> analyze(
        @PathVariable UUID id,
        @RequestParam UUID tenantId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(aiAnalysisService.analyze(id, tenantId));
    }

    @GetMapping("/{id}/analyze/latest")
    @Operation(summary = "Get the most recently generated AI analysis for the incident.")
    public ApiResponse<AIAnalysisResponse> getLatestAnalysis(
        @PathVariable UUID id,
        @RequestParam UUID tenantId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(aiAnalysisService.getLatest(id, tenantId));
    }

    @PostMapping("/{id}/runbook")
    @Operation(summary = "Generate a safe, evidence-aware runbook suggestion for the incident. Each call creates a new version.")
    public ApiResponse<RunbookSuggestionResponse> generateRunbook(
        @PathVariable UUID id,
        @RequestParam UUID tenantId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(runbookService.generate(id, tenantId));
    }

    @GetMapping("/{id}/runbook/latest")
    @Operation(summary = "Get the most recently generated runbook suggestion for the incident.")
    public ApiResponse<RunbookSuggestionResponse> getLatestRunbook(
        @PathVariable UUID id,
        @RequestParam UUID tenantId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(runbookService.getLatest(id, tenantId));
    }

    @PostMapping("/{id}/postmortem")
    @Operation(summary = "Generate a structured postmortem draft for the incident. Each call creates a new version. Enriched automatically if an AI analysis exists.")
    public ApiResponse<PostmortemDraftResponse> generatePostmortem(
        @PathVariable UUID id,
        @RequestParam UUID tenantId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(postmortemService.generate(id, tenantId));
    }

    @GetMapping("/{id}/postmortem/latest")
    @Operation(summary = "Get the most recently generated postmortem draft for the incident.")
    public ApiResponse<PostmortemDraftResponse> getLatestPostmortem(
        @PathVariable UUID id,
        @RequestParam UUID tenantId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(postmortemService.getLatest(id, tenantId));
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "List the incident's lifecycle timeline (created, severity changes, pages, resolution), oldest first.")
    public ApiResponse<List<IncidentTimelineResponse>> getTimeline(
        @PathVariable UUID id,
        @RequestParam UUID tenantId
    ) {
        TenantContext.requireOwn(tenantId);
        // 404 for an unknown or other-tenant incident, rather than an empty list that looks
        // like an incident with no history. findById filters by TenantContext.get().
        incidentService.findById(id);
        return ApiResponse.success(incidentTimelineService.findForIncident(tenantId, id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Soft delete an incident")
    public void delete(@PathVariable UUID id, @RequestParam UUID tenantId) {
        // See getById — controller-level check is defense in depth, not the primary
        // control. IncidentService.softDelete filters by TenantContext.get() in the query.
        TenantContext.requireOwn(tenantId);
        incidentService.softDelete(id);
    }


}
