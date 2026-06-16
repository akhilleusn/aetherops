package com.aetherops.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Slf4j
public class ContentSizeLimitFilter extends OncePerRequestFilter {

    static final long MAX_BODY_BYTES = 1024 * 1024L; // 1 MB

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String method = request.getMethod();
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            int contentLength = request.getContentLengthLong() > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : (int) request.getContentLengthLong();
            if (contentLength > MAX_BODY_BYTES) {
                log.warn("Request body too large method={} path={} size={}",
                        method, request.getRequestURI(), contentLength);
                response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"status\":413,\"message\":\"Request body exceeds 1 MB limit\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
