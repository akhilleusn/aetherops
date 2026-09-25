package com.aetherops.publicdemo;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.common.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.UUID;

/** GET-only by construction; PublicDemoSecurityTest fails the build if a non-GET mapping appears here. */
@RestController
@RequestMapping("/api/v1/public-demo")
@RequiredArgsConstructor
@Tag(name = "Public Demo", description = "Read-only view of the dedicated demo tenant. No authentication; credentials are rejected.")
public class PublicDemoController {

    private final PublicDemoService demoService;

    @GetMapping("/incidents")
    @Operation(summary = "List demo incidents, most recently active first (20 per page)")
    public ApiResponse<List<DemoViews.Incident>> incidents(@RequestParam(defaultValue = "0") int page) {
        return ApiResponse.success(demoService.listIncidents(page));
    }

    @GetMapping("/incidents/{id}")
    @Operation(summary = "Get a demo incident")
    public ApiResponse<DemoViews.Incident> incident(@PathVariable UUID id) {
        return ApiResponse.success(demoService.getIncident(id));
    }

    @GetMapping("/incidents/{id}/timeline")
    @Operation(summary = "Lifecycle timeline, oldest first")
    public ApiResponse<List<DemoViews.TimelineEvent>> timeline(@PathVariable UUID id) {
        return ApiResponse.success(demoService.getTimeline(id));
    }

    @GetMapping("/incidents/{id}/logs")
    @Operation(summary = "Raw log lines attached to the incident as evidence, oldest first")
    public ApiResponse<List<DemoViews.LogLine>> logs(@PathVariable UUID id) {
        return ApiResponse.success(demoService.getLogLines(id));
    }

    @GetMapping("/incidents/{id}/analysis")
    @Operation(summary = "Latest completed AI analysis")
    public ApiResponse<DemoViews.Analysis> analysis(@PathVariable UUID id) {
        return ApiResponse.success(demoService.getLatestAnalysis(id));
    }

    @GetMapping("/incidents/{id}/runbook")
    @Operation(summary = "Latest runbook suggestion")
    public ApiResponse<DemoViews.Runbook> runbook(@PathVariable UUID id) {
        return ApiResponse.success(demoService.getLatestRunbook(id));
    }

    @GetMapping("/incidents/{id}/postmortem")
    @Operation(summary = "Latest postmortem draft")
    public ApiResponse<DemoViews.Postmortem> postmortem(@PathVariable UUID id) {
        return ApiResponse.success(demoService.getLatestPostmortem(id));
    }

    // No global handler covers a malformed UUID/page (it would fall through to the generic 500).
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> badArgument(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, "Invalid " + ex.getName(), request.getRequestURI()));
    }
}
