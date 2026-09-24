package com.aetherops.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production runs behind a GCP external Application Load Balancer, which appends
 * "&lt;client&gt;, &lt;lb-ip&gt;" to X-Forwarded-For, so it uses trustedProxyCount = 2. These pin
 * that shape: the client entry is resolved, and nothing the caller writes further left can
 * move it. (Single-hop, count = 1 cases are covered in IpRateLimitFilterTest.)
 */
class ClientIpResolverTest {

    private static final String CLIENT = "203.0.113.7";
    private static final String LB     = "34.120.0.1";
    private static final String SOCKET = "169.254.1.1";

    private final ClientIpResolver twoHops = new ClientIpResolver(2);

    @Test
    void loadBalancerChain_resolvesClient_notLoadBalancer() {
        assertThat(twoHops.resolve(request(CLIENT + ", " + LB))).isEqualTo(CLIENT);
    }

    @Test
    void forgedLeftEntries_doNotChangeResolvedClient() {
        assertThat(twoHops.resolve(request("1.1.1.1, " + CLIENT + ", " + LB))).isEqualTo(CLIENT);
        assertThat(twoHops.resolve(request("9.9.9.9, 8.8.8.8, " + CLIENT + ", " + LB))).isEqualTo(CLIENT);
    }

    // The bug this configuration fixes: count = 1 behind a two-entry load balancer resolves
    // every visitor to the load balancer's own address, i.e. one shared rate-limit bucket.
    @Test
    void countOfOne_behindLoadBalancer_resolvesLoadBalancerIp() {
        assertThat(new ClientIpResolver(1).resolve(request(CLIENT + ", " + LB))).isEqualTo(LB);
    }

    @Test
    void chainShorterThanCount_fallsBackToRemoteAddress() {
        assertThat(twoHops.resolve(request(CLIENT))).isEqualTo(SOCKET);
    }

    @Test
    void noHeader_usesRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(SOCKET);
        assertThat(twoHops.resolve(request)).isEqualTo(SOCKET);
    }

    @Test
    void whitespaceAroundEntries_isTrimmed() {
        assertThat(twoHops.resolve(request("  " + CLIENT + "  ,  " + LB + " "))).isEqualTo(CLIENT);
    }

    private static MockHttpServletRequest request(String xForwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(SOCKET);
        request.addHeader("X-Forwarded-For", xForwardedFor);
        return request;
    }
}
