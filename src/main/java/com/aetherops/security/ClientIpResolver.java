package com.aetherops.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for resolving the real client IP from X-Forwarded-For, shared by
 * every filter that needs it (IpRateLimitFilter, RateLimitResponseFilter) so they can't drift.
 *
 * X-Forwarded-For is a comma-separated chain: each trusted proxy hop APPENDS the address it
 * saw its incoming connection come from. So the last app.security.trusted-proxy-count entries
 * were written by infrastructure we control and can be trusted; everything to the left of that
 * is whatever the original caller supplied (or an earlier, untrusted hop), and is fully
 * spoofable. The previous implementation trusted chain[0] unconditionally — the leftmost,
 * always-attacker-controlled entry — letting anyone reset their own rate-limit bucket by
 * sending a different fake value on every request.
 *
 * With N trusted hops, the real client is at index (chain.length - N): e.g. one trusted proxy
 * that appends the client's own address as the LAST entry needs N = 1. The live deployment is
 * a GCP external Application Load Balancer in front of Cloud Run (ingress restricted to the
 * load balancer, so no other path reaches the app), which appends "&lt;client&gt;, &lt;lb-ip&gt;"
 * -- two trusted entries, so production sets N via APP_SECURITY_TRUSTED_PROXY_COUNT rather
 * than relying on the default of 1. The right N depends on every path that can reach the app:
 * with two paths that append different numbers of entries, no single N is correct. Bounds-
 * checked: if the chain is shorter than trustedProxyCount (or trustedProxyCount is 0,
 * disabling XFF trust entirely), fall back to request.getRemoteAddr() rather than reading out
 * of range.
 *
 * DEBUG logging (off by default) records each resolution -- chain length, resolved IP, and
 * socket address -- so N can be verified against real traffic: enable it on
 * {@code com.aetherops.security.ClientIpResolver} only while checking, since it logs IPs.
 */
@Component
@Slf4j
public class ClientIpResolver {

    private final int trustedProxyCount;

    public ClientIpResolver(@Value("${app.security.trusted-proxy-count:1}") int trustedProxyCount) {
        this.trustedProxyCount = trustedProxyCount;
    }

    public String resolve(HttpServletRequest request) {
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            log.debug("No X-Forwarded-For; resolved={} (remote address)", request.getRemoteAddr());
            return request.getRemoteAddr();
        }

        String[] chain = header.split(",");
        int index = chain.length - trustedProxyCount;
        if (index < 0 || index >= chain.length) {
            log.warn("X-Forwarded-For chain length ({}) incompatible with trusted proxy count ({}); falling back to remote address",
                chain.length, trustedProxyCount);
            return request.getRemoteAddr();
        }
        String resolved = chain[index].trim();
        if (log.isDebugEnabled()) {
            log.debug("X-Forwarded-For entries={} trustedProxyCount={} resolved={} remoteAddr={} chain=[{}]",
                chain.length, trustedProxyCount, resolved, request.getRemoteAddr(), header);
        }
        return resolved;
    }
}
