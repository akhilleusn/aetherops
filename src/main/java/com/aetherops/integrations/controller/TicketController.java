package com.aetherops.integrations.controller;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.integrations.model.CreateTicketRequest;
import com.aetherops.integrations.service.JiraTicketService;
import com.aetherops.integrations.service.ServiceNowTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/incidents/{incidentId}/tickets")
@RequiredArgsConstructor
@Tag(name = "Tickets", description = "Manually create a Jira or ServiceNow ticket for an incident")
public class TicketController {

    private final JiraTicketService jiraTicketService;
    private final ServiceNowTicketService serviceNowTicketService;

    @PostMapping("/jira")
    @Operation(summary = "Create a Jira issue for this incident")
    public ApiResponse<Map<String, String>> createJiraTicket(
        @PathVariable UUID tenantId, @PathVariable UUID incidentId,
        @Valid @RequestBody CreateTicketRequest request
    ) {
        String ticketId = jiraTicketService.createIssue(tenantId, incidentId, request.getSummary(), request.getDescription())
            .orElseThrow(() -> new ResourceNotFoundException("No enabled Jira integration configured for tenant: " + tenantId));
        return ApiResponse.success(Map.of("ticketId", ticketId));
    }

    @PostMapping("/servicenow")
    @Operation(summary = "Create a ServiceNow incident ticket for this incident")
    public ApiResponse<Map<String, String>> createServiceNowTicket(
        @PathVariable UUID tenantId, @PathVariable UUID incidentId,
        @Valid @RequestBody CreateTicketRequest request
    ) {
        String ticketId = serviceNowTicketService.createIncidentTicket(tenantId, incidentId, request.getSummary(), request.getDescription())
            .orElseThrow(() -> new ResourceNotFoundException("No enabled ServiceNow integration configured for tenant: " + tenantId));
        return ApiResponse.success(Map.of("ticketId", ticketId));
    }
}
