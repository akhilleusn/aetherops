package com.aetherops.publicdemo;

import com.aetherops.common.response.ErrorResponse;
import com.aetherops.ratelimit.SlidingWindowRateLimiterService;
import com.aetherops.security.ClientIpResolver;
import com.aetherops.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Gate for every /api/v1/public-demo/** request. Modelled on ApiKeyAuthFilter -- it pins
 * TenantContext server-side and clears it in finally -- with one deliberate difference: it never
 * authenticates anything. The request stays anonymous, so every route outside this prefix still
 * gets SecurityConfig's anyRequest().authenticated() 401, and only the GET permitAll for this
 * prefix lets it reach PublicDemoController.
 *
 * Checks, in order: demo disabled -> 404; not GET -> 405; any credential header -> 400;
 * per-IP rate limit -> 429. Only then is TenantContext set, to the configured demo tenant --
 * never to anything taken from the request. Rejected requests don't consume rate-limit quota.
 */
@Component
@RequiredArgsConstructor
public class PublicDemoFilter extends OncePerRequestFilter {

    public static final String PATTERN = "/api/v1/public-demo/**";
    private static final RequestMatcher PATHS = new AntPathRequestMatcher(PATTERN);

    private final PublicDemoProperties            properties;
    private final SlidingWindowRateLimiterService rateLimiter;
    private final ClientIpResolver                clientIpResolver;
    private final ObjectMapper                    objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATHS.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<UUID> demoTenant = properties.tenantId();
        if (demoTenant.isEmpty()) {
            reject(request, response, HttpStatus.NOT_FOUND, "Not found");
            return;
        }
        if (!HttpMethod.GET.matches(request.getMethod())) {
            response.setHeader(HttpHeaders.ALLOW, HttpMethod.GET.name());
            reject(request, response, HttpStatus.METHOD_NOT_ALLOWED, "The public demo is read-only");
            return;
        }
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null || request.getHeader("X-Api-Key") != null) {
            reject(request, response, HttpStatus.BAD_REQUEST, "Credentials are not accepted on public demo routes");
            return;
        }
        String key = "public-demo:ip:" + clientIpResolver.resolve(request);   // per IP, not per path
        if (!rateLimiter.isAllowed(key, properties.requestsPerWindow(), properties.windowSeconds())) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(properties.windowSeconds()));
            reject(request, response, HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again later.");
            return;
        }
        try {
            TenantContext.set(demoTenant.get());
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(status, message, request.getRequestURI()));
    }
}
