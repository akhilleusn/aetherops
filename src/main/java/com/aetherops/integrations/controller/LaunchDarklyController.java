package com.aetherops.integrations.controller;

import com.aetherops.apikey.model.ApiKey;
import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.integrations.model.FlagEventResponse;
import com.aetherops.integrations.model.LaunchDarklyWebhookRequest;
import com.aetherops.integrations.service.LaunchDarklyFlagEventService;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "LaunchDarkly", description = "Feature-flag change events, correlated against incident timing")
public class LaunchDarklyController {

    private final ApiKeyService apiKeyService;
    private final LaunchDarklyFlagEventService flagEventService;

    @PostMapping("/api/v1/integrations/launchdarkly")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "LaunchDarkly audit-log webhook — records flag changes for incident correlation")
    public void webhook(@RequestBody LaunchDarklyWebhookRequest request,
                         @RequestHeader("X-Api-Key") String apiKeyHeader) {
        ApiKey apiKey = apiKeyService.resolve(apiKeyHeader);
        flagEventService.record(apiKey.getTenantId(), request);
    }

    @GetMapping("/api/v1/tenants/{tenantId}/launchdarkly-events")
    @Operation(summary = "List recent flag-change events for a tenant. Defaults to the last 24 hours.")
    public ApiResponse<List<FlagEventResponse>> list(
        @PathVariable UUID tenantId,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to
    ) {
        TenantContext.requireOwn(tenantId);
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(24, ChronoUnit.HOURS);
        return ApiResponse.success(flagEventService.findInRange(tenantId, effectiveFrom, effectiveTo));
    }
}
