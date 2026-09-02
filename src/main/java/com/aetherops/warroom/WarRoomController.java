package com.aetherops.warroom;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/war-rooms")
@RequiredArgsConstructor
@Tag(name = "War Rooms", description = "Shared collaboration space for an incident: participants and status, polled rather than pushed")
public class WarRoomController {

    private final WarRoomService warRoomService;

    @PostMapping("/incidents/{incidentId}")
    @Operation(summary = "Manually open a war room for an incident (auto-opened for CRITICAL incidents otherwise)")
    public ApiResponse<WarRoomResponse> open(
        @PathVariable UUID tenantId,
        @PathVariable UUID incidentId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(warRoomService.open(tenantId, incidentId, "MANUAL"));
    }

    @GetMapping("/{warRoomId}")
    @Operation(summary = "Get a war room's current state, including active participants")
    public ApiResponse<WarRoomResponse> get(
        @PathVariable UUID tenantId,
        @PathVariable UUID warRoomId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(warRoomService.get(tenantId, warRoomId));
    }

    @GetMapping
    @Operation(summary = "List war rooms for a tenant, defaulting to ACTIVE")
    public ApiResponse<List<WarRoomResponse>> list(
        @PathVariable UUID tenantId,
        @RequestParam(required = false, defaultValue = "ACTIVE") WarRoomStatus status
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(warRoomService.list(tenantId, status));
    }

    @PostMapping("/{warRoomId}/participants")
    @Operation(summary = "Join a war room")
    public ApiResponse<WarRoomResponse> join(
        @PathVariable UUID tenantId,
        @PathVariable UUID warRoomId,
        @Valid @RequestBody JoinWarRoomRequest request
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(warRoomService.join(tenantId, warRoomId, request.getUserId(), request.getRole()));
    }

    @DeleteMapping("/{warRoomId}/participants/{userId}")
    @Operation(summary = "Leave a war room")
    public ApiResponse<WarRoomResponse> leave(
        @PathVariable UUID tenantId,
        @PathVariable UUID warRoomId,
        @PathVariable UUID userId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(warRoomService.leave(tenantId, warRoomId, userId));
    }

    @PostMapping("/{warRoomId}/close")
    @Operation(summary = "Close a war room")
    public ApiResponse<WarRoomResponse> close(
        @PathVariable UUID tenantId,
        @PathVariable UUID warRoomId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(warRoomService.close(tenantId, warRoomId));
    }
}
