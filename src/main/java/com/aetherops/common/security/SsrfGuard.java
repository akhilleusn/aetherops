package com.aetherops.common.security;

import com.aetherops.common.exception.SsrfBlockedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Phase 2 remediation, findings 4/6/11 (see STEP_REPORT.md): a single, shared SSRF check used
 * by every outbound call in this codebase whose target URL comes from tenant/user
 * configuration or a third-party payload (Jira/ServiceNow ticket integrations, the outbound
 * webhook-delivery feature) — one component so the three call sites can't drift apart, per
 * instruction. Deliberately does NOT cover {@code AwsSnsHostValidator}'s job (a fixed,
 * single-provider hostname allowlist) or Slack's {@code response_url} host check (same
 * shape, different fixed provider) — those stay their own narrow, purpose-specific checks.
 *
 * <p><b>What this enforces:</b> HTTPS only (HTTP only if the caller explicitly opts in via
 * {@code allowHttp} — no call site in this codebase does), the scheme's standard port only
 * (443/80 — nothing here needs a non-standard port today), and every DNS answer for the host
 * resolved OUTSIDE all private/loopback/link-local/reserved ranges: 127.0.0.0/8, 10.0.0.0/8,
 * 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16 (link-local — covers the cloud-metadata
 * endpoint 169.254.169.254), 0.0.0.0/8, 100.64.0.0/10 (carrier-grade NAT), ::1, fc00::/7
 * (unique-local), fe80::/10, and IPv4-mapped IPv6 forms (unwrapped and re-checked as IPv4, so
 * a mapped form can't smuggle a private IPv4 address past the IPv4-shaped checks).
 *
 * <p><b>DNS-rebinding tradeoff, made explicitly, not left implicit:</b> this resolves DNS
 * itself and validates every answer, but does NOT pin the subsequent HTTP request to that
 * exact resolved address — the actual request still goes to the hostname, which the HTTP
 * client resolves again internally. True pinning (connect to the validated IP while still
 * presenting the original hostname for TLS SNI and certificate verification) has no supported
 * hook in plain JDK {@code HttpClient} (what this app's {@code RestClient} uses — no Apache
 * HttpClient5/OkHttp/Jetty client is on the classpath) without hand-built low-level
 * SNI/socket-factory code, which risks a worse mistake (e.g. weakened hostname verification)
 * than the gap it would close. Given the explicit choice offered and taken: validate
 * immediately before every request (both at configuration time AND again at request time,
 * since DNS for an already-accepted host can be repointed later — every call site in this
 * remediation does both) rather than build a pinned connection path. This closes the
 * realistic case for these call sites (a base URL/webhook URL pointed at a private address,
 * which is static config here, not something that flips every few seconds) and leaves an
 * explicitly-documented, narrow residual window against a sophisticated attacker who controls
 * the target domain's DNS with very short TTLs and flips it between this check and the HTTP
 * client's own subsequent resolution moments later.
 *
 * <p><b>Redirects:</b> disabled outright, not re-validated per hop — see
 * {@link #disableRedirects}. Simpler than per-hop re-validation and just as effective here: a
 * disabled redirect surfaces as a 3xx response to the caller instead of being silently
 * followed, and every call site in this remediation treats a 3xx as a rejected/failed
 * response rather than parsing its body.
 */
@Slf4j
public final class SsrfGuard {

    private SsrfGuard() {
    }

    /** @see #validate(String, boolean) */
    public static URI validate(String rawUrl) {
        return validate(rawUrl, false);
    }

    /**
     * Validates {@code rawUrl} is safe to fetch server-side. Resolves DNS as part of
     * validation — call this again immediately before firing the actual request (using the
     * same hostname, never the resolved address itself) rather than trusting a validation
     * performed earlier. Throws {@link SsrfBlockedException} (mapped to 400 by
     * {@code GlobalExceptionHandler}) on any failure; every rejection is also logged here so
     * callers don't need to log it again to get a clear signal of why a request was blocked.
     */
    public static URI validate(String rawUrl, boolean allowHttp) {
        if (rawUrl == null || rawUrl.isBlank()) {
            log.warn("SSRF guard rejected a blank/null URL");
            throw new SsrfBlockedException("URL must not be blank");
        }

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (Exception e) {
            log.warn("SSRF guard rejected URL: malformed [rule: malformed-url]");
            throw new SsrfBlockedException("Malformed URL");
        }

        String scheme = uri.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean http = "http".equalsIgnoreCase(scheme);
        if (!https && !(allowHttp && http)) {
            log.warn("SSRF guard rejected URL for host {}: disallowed scheme '{}' [rule: scheme]", uri.getHost(), scheme);
            throw new SsrfBlockedException(
                "URL scheme must be https" + (allowHttp ? " or http" : "") + ", was: " + scheme);
        }

        int standardPort = https ? 443 : 80;
        int port = uri.getPort();
        if (port != -1 && port != standardPort) {
            log.warn("SSRF guard rejected URL for host {}: non-standard port {} [rule: port]", uri.getHost(), port);
            throw new SsrfBlockedException("URL port must be the scheme's default (" + standardPort + "), was: " + port);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            log.warn("SSRF guard rejected URL: no host [rule: no-host]");
            throw new SsrfBlockedException("URL has no host");
        }

        // DNS-rebinding note (see this class's javadoc for the full tradeoff): resolved here,
        // right before use, but NOT pinned -- the actual request below re-resolves this same
        // hostname independently. This narrows the window to "check, then immediately connect"
        // rather than closing it; a full mitigation would need a pinned connection path, which
        // this codebase's plain JDK HttpClient has no supported hook for.
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            log.warn("SSRF guard rejected URL for host {}: could not resolve [rule: unresolvable]", host);
            throw new SsrfBlockedException("Could not resolve host: " + host);
        }

        for (InetAddress address : addresses) {
            String reason = disallowedReason(address);
            if (reason != null) {
                // Deliberately WARN, not DEBUG: a tenant's legitimate integration sitting
                // behind a private IP on their own network (e.g. an on-prem Jira on 10.x) will
                // be blocked by this guard — that's the correct default (see class javadoc /
                // STEP_REPORT.md), but it must be diagnosable from logs, not a silent failure
                // someone has to debug blind. If this ever needs supporting, the fix is an
                // explicit per-tenant allowlist, not weakening this check.
                log.warn("SSRF guard rejected URL for host {}: resolved address {} matched rule '{}'",
                    host, address.getHostAddress(), reason);
                throw new SsrfBlockedException("Host resolves to a private/reserved address: " + host);
            }
        }

        return uri;
    }

    /**
     * Builds a {@code RestClient.Builder} that never follows redirects — a validated URL that
     * later 3xx's must not be silently chased to wherever it points. Every call site in this
     * remediation applies this to its own injected {@code RestClient.Builder} (which Spring
     * hands out as a fresh instance per injection point) rather than to any shared/global
     * builder, so this has no effect on unrelated outbound calls elsewhere in the app.
     *
     * <p>Step H2 (see STEP_REPORT.md): this replaces the builder's {@code requestFactory}
     * wholesale, which used to silently discard any connect/read timeout a caller had
     * configured on the builder before passing it here — exactly the three call sites that
     * most needed a bounded timeout (Jira, ServiceNow, webhook delivery) were also the three
     * routed through this method. Timeouts are now a required parameter here instead, so
     * "no redirects" and "has a timeout" compose instead of one erasing the other.
     */
    public static RestClient.Builder disableRedirects(RestClient.Builder builder, Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return builder.requestFactory(factory);
    }

    /** Returns the name of the rule that disallows {@code address}, or {@code null} if it's
     *  allowed — the name is included in the WARN log at the call site so a rejection (e.g. a
     *  tenant's on-prem Jira sitting on 10.x) is diagnosable from logs, not a silent failure. */
    private static String disallowedReason(InetAddress address) {
        byte[] bytes = address.getAddress();

        if (address instanceof Inet6Address && bytes.length == 16 && isIpv4Mapped(bytes)) {
            byte[] v4 = {bytes[12], bytes[13], bytes[14], bytes[15]};
            try {
                String innerReason = disallowedReason(InetAddress.getByAddress(v4));
                return innerReason != null ? "ipv4-mapped-" + innerReason : null;
            } catch (UnknownHostException e) {
                return "unresolvable-ipv4-mapped"; // fail closed
            }
        }

        if (address.isLoopbackAddress()) return "loopback";                 // 127.0.0.0/8, ::1
        if (address.isLinkLocalAddress()) return "link-local";              // 169.254.0.0/16 (covers 169.254.169.254), fe80::/10
        if (address.isSiteLocalAddress()) return "private-range";           // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
        if (address.isAnyLocalAddress()) return "any-local";                // 0.0.0.0, ::
        if (address.isMulticastAddress()) return "multicast";

        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0) return "reserved-0.0.0.0/8";
            if (first == 100 && (second & 0xC0) == 64) return "carrier-grade-nat";  // 100.64.0.0/10
        }

        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) return "unique-local-ipv6"; // fc00::/7
        }

        return null;
    }

    private static boolean isIpv4Mapped(byte[] b16) {
        for (int i = 0; i < 10; i++) {
            if (b16[i] != 0) return false;
        }
        return (b16[10] & 0xFF) == 0xFF && (b16[11] & 0xFF) == 0xFF;
    }
}
