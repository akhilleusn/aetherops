package com.aetherops.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContentSizeLimitFilterTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;
    @InjectMocks private ContentSizeLimitFilter filter;

    @Test
    void allowsPost_whenUnderLimit() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentLengthLong()).thenReturn(512L);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(413);
    }

    @Test
    void rejects_whenPostBodyExceedsLimit() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentLengthLong()).thenReturn(2 * 1024 * 1024L);
        when(request.getRequestURI()).thenReturn("/api/v1/incidents");

        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(413);
        verify(chain, never()).doFilter(any(), any());
        assertThat(sw.toString()).contains("413");
    }

    @Test
    void rejects_whenPutBodyExceedsLimit() throws Exception {
        when(request.getMethod()).thenReturn("PUT");
        when(request.getContentLengthLong()).thenReturn(ContentSizeLimitFilter.MAX_BODY_BYTES + 1);
        when(request.getRequestURI()).thenReturn("/api/v1/incidents/123");

        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(413);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void skipsCheck_forGetRequests() throws Exception {
        when(request.getMethod()).thenReturn("GET");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }
}
