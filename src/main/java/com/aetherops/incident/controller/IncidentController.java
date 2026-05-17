package com.aetherops.incident.controller;

import com.aetherops.common.response.ApiResponse;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new incident")
    public ApiResponse<IncidentResponse> create(@Valid @RequestBody CreateIncidentRequest request) {
        return ApiResponse.success(incidentService.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get incident by ID")
    public ApiResponse<IncidentResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(incidentService.findById(id));
    }

    @GetMapping
    @Operation(summary = "List incidents with optional filtering by status and serviceId")
    public ApiResponse<Page<IncidentResponse>> list(
        @RequestParam(required = false) IncidentStatus status,
        @RequestParam(required = false) String serviceId,
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(incidentService.findAll(status, serviceId, pageable));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update incident title, description, status, or severity")
    public ApiResponse<IncidentResponse> update(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateIncidentRequest request
    ) {
        return ApiResponse.success(incidentService.update(id, request));
    }
}
