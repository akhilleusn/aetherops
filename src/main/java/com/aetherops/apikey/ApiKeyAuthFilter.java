package com.aetherops.apikey;

import com.aetherops.apikey.model.ApiKey;
import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.common.exception.InvalidApiKeyException;
import com.aetherops.common.response.ErrorResponse;
import com.aetherops.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Api-Key";

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String rawKey = request.getHeader(HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        ApiKey apiKey;
        try {
            apiKey = apiKeyService.resolve(rawKey);

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "apikey:" + apiKey.getTenantId(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))
            );
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);

            apiKeyService.recordLastUsed(apiKey.getId());
            log.debug("API key authenticated keyId={} tenantId={}", apiKey.getId(), apiKey.getTenantId());

        } catch (InvalidApiKeyException e) {
            log.warn("Invalid or expired API key presented path={}", request.getRequestURI());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Invalid or expired API key", request.getRequestURI()));
            return;
        } catch (Exception e) {
            log.error("Unexpected error during API key authentication", e);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Authentication error", request.getRequestURI()));
            return;
        }

        // Webhook/ingestion endpoints authenticate via this header instead of a JWT, so
        // TenantContextFilter (which only reads a Bearer token) never runs for them — without
        // this, app.tenant_id is never propagated to Postgres RLS for these requests at all.
        try {
            TenantContext.set(apiKey.getTenantId());
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
