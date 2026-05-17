package com.aetherops.ingestion.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogEventResponse;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.service.LogEventService;
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

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Tag(name = "Log Events", description = "Ingest and query structured log events")
public class LogEventController {

    private final LogEventService logEventService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ingest a log event from a backend service")
    public ApiResponse<LogEventResponse> ingest(@Valid @RequestBody LogEventRequest request) {
        return ApiResponse.success(logEventService.ingest(request));
    }

    @GetMapping
    @Operation(summary = "List log events with optional filtering by serviceId and level")
    public ApiResponse<Page<LogEventResponse>> list(
        @RequestParam(required = false) String serviceId,
        @RequestParam(required = false) LogLevel level,
        @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(logEventService.findAll(serviceId, level, pageable));
    }
}
