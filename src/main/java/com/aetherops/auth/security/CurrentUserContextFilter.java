package com.aetherops.auth.security;

import com.aetherops.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Populates {@link CurrentUserContext} from the request's JWT, the same way
 * {@code com.aetherops.tenant.TenantContextFilter} populates {@code TenantContext} — a
 * separate filter (rather than folding this into TenantContextFilter) so each keeps a single,
 * obvious responsibility.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CurrentUserContextFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                if (jwtService.isTokenValid(token) && !jwtService.isPartialToken(token)) {
                    UUID userId = jwtService.extractUserId(token);
                    CurrentUserContext.set(userId);
                    log.debug("CurrentUserContext set userId={} path={}", userId, request.getRequestURI());
                }
            }
            chain.doFilter(request, response);
        } finally {
            CurrentUserContext.clear();
        }
    }
}
