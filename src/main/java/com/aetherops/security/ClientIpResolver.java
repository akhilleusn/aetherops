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
 * (the default — Railway is the live deployment target) appends the client's own address as
 * the LAST entry, so chain.length - 1 is correct. Bounds-checked: if the chain is shorter than
 * trustedProxyCount (or trustedProxyCount is 0, disabling XFF trust entirely), fall back to
 * request.getRemoteAddr() rather than reading out of range.
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
            return request.getRemoteAddr();
        }

        String[] chain = header.split(",");
        int index = chain.length - trustedProxyCount;
        if (index < 0 || index >= chain.length) {
            log.warn("X-Forwarded-For chain length ({}) incompatible with trusted proxy count ({}); falling back to remote address",
                chain.length, trustedProxyCount);
            return request.getRemoteAddr();
        }
        return chain[index].trim();
    }
}
