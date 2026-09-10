package com.aetherops.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * Phase 2 remediation, findings 1 and 8 (see STEP_REPORT.md): X-Trace-Id must be validated as
 * a canonical UUID, not accepted and reflected/logged verbatim.
 */
@ExtendWith(MockitoExtension.class)
class GatewayFilterTest {

    @InjectMocks
    private GatewayFilter filter;

    @Test
    void malformedTraceId_isReplacedNotReflected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "<script>alert(1)</script>");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        String traceId = response.getHeader("X-Trace-Id");
        assertThat(traceId).isNotEqualTo("<script>alert(1)</script>");
        assertThat(traceId).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    @Test
    void nonUuidButPlausibleTraceId_isReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "not-a-real-uuid-at-all");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        String traceId = response.getHeader("X-Trace-Id");
        assertThat(traceId).isNotEqualTo("not-a-real-uuid-at-all");
        assertThat(UUID.fromString(traceId)).isNotNull();
    }

    @Test
    void validUuidTraceId_isPreserved() throws Exception {
        String validUuid = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", validUuid);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(validUuid);
    }

    @Test
    void missingTraceId_generatesFreshUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        String traceId = response.getHeader("X-Trace-Id");
        assertThat(UUID.fromString(traceId)).isNotNull();
    }

    @Test
    void doesNotLogOrReflectAttackerContentIntoDownstreamChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "evil\nX-Injected-Header: true");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        String traceId = response.getHeader("X-Trace-Id");
        assertThat(traceId).doesNotContain("evil");
        assertThat(traceId).doesNotContain("\n");
    }
}
