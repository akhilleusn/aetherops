package com.aetherops.escalation;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/incidents/{incidentId}/escalations")
@RequiredArgsConstructor
@Tag(name = "Auto-Escalation", description = "Escalate unresolved incidents that no one has acknowledged")
public class EscalationHistoryController {

    private final EscalationExecutionRepository executionRepository;

    @GetMapping
    @Operation(summary = "List the escalation history for an incident, oldest first")
    public ApiResponse<List<EscalationExecutionResponse>> history(
        @PathVariable UUID tenantId,
        @PathVariable UUID incidentId
    ) {
        TenantContext.requireOwn(tenantId);
        List<EscalationExecutionResponse> executions = executionRepository
            .findByTenantIdAndIncidentIdOrderByEscalationLevelAsc(tenantId, incidentId).stream()
            .map(e -> EscalationExecutionResponse.builder()
                .id(e.getId())
                .incidentId(e.getIncidentId())
                .escalationLevel(e.getEscalationLevel())
                .escalatedAt(e.getEscalatedAt())
                .build())
            .toList();
        return ApiResponse.success(executions);
    }
}
