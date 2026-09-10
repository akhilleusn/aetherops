package com.aetherops.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Slf4j
public class GatewayFilter extends OncePerRequestFilter {

    private static final String TRACE_HEADER = "X-Trace-Id";

    /**
     * Phase 2 remediation, findings 1 and 8 (see STEP_REPORT.md): {@code X-Trace-Id} is
     * attacker-controlled (unauthenticated, arrives before any auth filter) and was previously
     * accepted verbatim — unbounded length, arbitrary content — then written into every log
     * line for the request AND reflected back in the response header. A caller could inject log
     * forging content or anything else via this header. Validating strictly against canonical
     * UUID form (not {@link UUID#fromString}, which has known JDK edge cases throwing
     * non-{@code IllegalArgumentException} on some malformed input) closes both at once: a
     * malformed value is discarded and replaced, never sanitized-and-kept, so nothing
     * attacker-chosen ever reaches a log line or the response.
     */
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        long start = System.currentTimeMillis();
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || !UUID_PATTERN.matcher(traceId).matches()) {
            traceId = UUID.randomUUID().toString();
        }

        response.setHeader(TRACE_HEADER, traceId);
        MDC.put("traceId", traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            log.info("traceId={} requestId={} method={} path={} status={} durationMs={}",
                    traceId, MDC.get("requestId"), request.getMethod(), request.getRequestURI(),
                    response.getStatus(), durationMs);
            MDC.remove("traceId");
        }
    }
}
