package com.aetherops.ingestion.controller;

import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.common.exception.MissingTenantIdException;
import com.aetherops.common.exception.RateLimitExceededException;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogEventResponse;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.service.LogEventService;
import com.aetherops.ratelimit.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Tag(name = "Log Events", description = "Ingest and query structured log events")
public class LogEventController {

    private final LogEventService logEventService;
    private final ApiKeyService   apiKeyService;
    private final RateLimiter     rateLimiter;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ingest a log event — authenticate via X-Api-Key header or tenantId in body")
    public ApiResponse<LogEventResponse> ingest(
        @Valid @RequestBody LogEventRequest request,
        @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
        HttpServletResponse response
    ) {
        String rateLimitKey;

        if (apiKey != null) {
            var key = apiKeyService.resolve(apiKey);
            request.setTenantId(key.getTenantId());
            request.setProjectId(key.getProjectId());
            rateLimitKey = "key:" + key.getId();
        } else if (request.getTenantId() != null) {
            rateLimitKey = "tenant:" + request.getTenantId();
        } else {
            throw new MissingTenantIdException();
        }

        RateLimiter.Result rl = rateLimiter.tryConsume(rateLimitKey);
        response.setHeader("X-RateLimit-Limit",     String.valueOf(rateLimiter.getLimit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(rl.remaining()));
        response.setHeader("X-RateLimit-Reset",     String.valueOf(rl.resetAt()));

        if (!rl.allowed()) {
            throw new RateLimitExceededException();
        }

        return ApiResponse.success(logEventService.ingest(request));
    }

    @GetMapping
    @Operation(summary = "List log events for a tenant, with optional filtering by serviceId and level")
    public ApiResponse<Page<LogEventResponse>> list(
        @RequestParam UUID tenantId,
        @RequestParam(required = false) String serviceId,
        @RequestParam(required = false) LogLevel level,
        @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(logEventService.findAll(tenantId, serviceId, level, pageable));
    }
}
