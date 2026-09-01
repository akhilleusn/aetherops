package com.aetherops.statuspage.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.statuspage.model.PublicStatusPageResponse;
import com.aetherops.statuspage.service.StatusPageService;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Public Status Page", description = "Unauthenticated public status page view")
public class PublicStatusPageController {

    private final StatusPageService statusPageService;

    @GetMapping("/api/v1/status-pages/{slug}")
    @Operation(summary = "Get the public status page by slug — no authentication required")
    public ApiResponse<PublicStatusPageResponse> get(@PathVariable String slug) {
        // This is an unauthenticated route (no JWT), so TenantContextFilter never populates
        // TenantContext — but getPublicPage() reads the RLS-protected incidents table, which
        // silently returns nothing without app.tenant_id set. Resolve tenantId from the
        // (non-RLS) status_pages table first, then set context before the real query.
        UUID tenantId = statusPageService.resolveTenantIdForSlug(slug);
        try {
            TenantContext.set(tenantId);
            return ApiResponse.success(statusPageService.getPublicPage(slug));
        } finally {
            TenantContext.clear();
        }
    }
}
