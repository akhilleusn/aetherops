package com.aetherops.incident.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.incident.model.*;
import com.aetherops.incident.service.IncidentCommandService;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/commands/incidents")
@RequiredArgsConstructor
@Tag(name = "Incident Commands", description = "CQRS write-side for incident commands")
public class IncidentCommandController {

    private final IncidentCommandService commandService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create incident via CQRS command")
    public ApiResponse<IncidentResponse> create(
            @Valid @RequestBody CreateIncidentCommand cmd,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        TenantContext.requireOwn(cmd.getTenantId());
        return ApiResponse.success(commandService.handle(cmd, idempotencyKey));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Resolve incident via CQRS command")
    public ApiResponse<IncidentResponse> resolve(
            @PathVariable("id") java.util.UUID incidentId,
            @RequestParam java.util.UUID tenantId
    ) {
        TenantContext.requireOwn(tenantId);
        ResolveIncidentCommand cmd = new ResolveIncidentCommand();
        cmd.setIncidentId(incidentId);
        cmd.setTenantId(tenantId);
        return ApiResponse.success(commandService.handle(cmd));
    }
}
