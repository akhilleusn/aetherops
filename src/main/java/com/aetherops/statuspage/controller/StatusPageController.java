package com.aetherops.statuspage.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.statuspage.model.CreateStatusPageRequest;
import com.aetherops.statuspage.model.PostStatusPageUpdateRequest;
import com.aetherops.statuspage.model.StatusPage;
import com.aetherops.statuspage.model.StatusPageIncidentUpdate;
import com.aetherops.statuspage.service.StatusPageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Status Page", description = "Public status page configuration and incident updates")
public class StatusPageController {

    private final StatusPageService statusPageService;

    @PostMapping("/api/v1/tenants/{tenantId}/status-page")
    @Operation(summary = "Create or reconfigure the tenant's public status page")
    public ApiResponse<StatusPage> configure(
        @PathVariable UUID tenantId,
        @Valid @RequestBody CreateStatusPageRequest request
    ) {
        return ApiResponse.success(statusPageService.configure(tenantId, request));
    }

    @PostMapping("/api/v1/incidents/{id}/status-page-updates")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Post a customer-facing update for an incident. The first update makes it visible on the public status page.")
    public ApiResponse<StatusPageIncidentUpdate> postUpdate(
        @PathVariable UUID id,
        @RequestParam UUID tenantId,
        @Valid @RequestBody PostStatusPageUpdateRequest request
    ) {
        return ApiResponse.success(statusPageService.postUpdate(tenantId, id, request));
    }
}
