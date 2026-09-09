package com.aetherops.ratelimit;

import com.aetherops.security.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IpRateLimitFilterTest {

    @Mock private SlidingWindowRateLimiterService slidingWindowRateLimiter;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private IpRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        // Real ClientIpResolver (not mocked) — one trusted proxy, matching the app's default —
        // so this test exercises the actual, corrected X-Forwarded-For resolution logic.
        filter = new IpRateLimitFilter(slidingWindowRateLimiter, new ClientIpResolver(1));
        ReflectionTestUtils.setField(filter, "limit", 20);
        ReflectionTestUtils.setField(filter, "windowSeconds", 60);
    }

    @Test
    void allowsRequest_whenUnderLimit() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(slidingWindowRateLimiter.isAllowed(anyString(), anyInt(), anyInt())).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void blocksRequest_whenOverLimit() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(slidingWindowRateLimiter.isAllowed(anyString(), anyInt(), anyInt())).thenReturn(false);

        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(429);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void skipsFilter_forNonProtectedPath() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/incidents");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(slidingWindowRateLimiter, never()).isAllowed(anyString(), anyInt(), anyInt());
    }

    @Test
    void usesXForwardedFor_takesTrustedEntry_notLeftmostAttackerControlledOne() throws Exception {
        // "203.0.113.1" is the leftmost entry — supplied by whoever sent the request, never
        // trustworthy. "10.0.0.1" is what the one trusted proxy (trustedProxyCount=1) actually
        // observed connecting to it, so that's the entry the filter must key rate-limiting on.
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1, 10.0.0.1");
        when(slidingWindowRateLimiter.isAllowed(anyString(), anyInt(), anyInt())).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(slidingWindowRateLimiter).isAllowed(contains("10.0.0.1"), anyInt(), anyInt());
    }

    @Test
    void forgedLeftmostEntry_doesNotChangeResolvedKey_soBucketIsNotReset() throws Exception {
        // An attacker varying only the spoofable leftmost entry, request after request, must
        // still land on the SAME rate-limit key — the trusted entry never moves.
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(slidingWindowRateLimiter.isAllowed(anyString(), anyInt(), anyInt())).thenReturn(true);

        when(request.getHeader("X-Forwarded-For")).thenReturn("1.1.1.1, 10.0.0.1");
        filter.doFilterInternal(request, response, filterChain);

        when(request.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9, 10.0.0.1");
        filter.doFilterInternal(request, response, filterChain);

        verify(slidingWindowRateLimiter, times(2)).isAllowed(contains("10.0.0.1"), anyInt(), anyInt());
    }
}
