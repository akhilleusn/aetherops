package com.aetherops.security;

import com.aetherops.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class RateLimitResponseFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);

        if (response.getHeader("X-RateLimit-Remaining") == null) {
            String key = clientIpResolver.resolve(request);
            RateLimiter.Result result = rateLimiter.peek(key);
            response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimiter.getLimit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
            response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetAt()));
        }
    }
}
