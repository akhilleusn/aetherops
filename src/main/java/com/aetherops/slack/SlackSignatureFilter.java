package com.aetherops.slack;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.Set;

/**
 * Rejects POSTs to the Slack interactivity/command endpoints that don't carry a
 * valid Slack request signature, before they reach the controllers. Wraps the
 * request in ContentCachingRequestWrapper and forces getParameterMap() first —
 * that's what makes Spring cache the raw form body for signature verification
 * while still letting @RequestParam binding work normally downstream.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlackSignatureFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of("/slack/interactivity", "/slack/commands");

    private final SlackRequestVerifier verifier;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PROTECTED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
        wrapped.getParameterMap();

        String timestamp = request.getHeader("X-Slack-Request-Timestamp");
        String signature = request.getHeader("X-Slack-Signature");

        if (!verifier.verify(timestamp, signature, wrapped.getContentAsByteArray())) {
            log.warn("Rejected Slack request with invalid signature path={}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        chain.doFilter(wrapped, response);
    }
}
