package com.aetherops.common.security;

import com.aetherops.common.exception.SsrfBlockedException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 remediation, findings 4/6/11 (see STEP_REPORT.md). All "accepted" and "rejected"
 * cases use IP literals (e.g. 8.8.8.8), never hostnames, so this test never depends on real
 * DNS resolution succeeding for a specific domain — {@code SsrfGuard.validate} genuinely
 * resolves whatever host it's given, and an IP literal needs no resolution at all.
 */
class SsrfGuardTest {

    private HttpServer testServer;

    @AfterEach
    void stopServer() {
        if (testServer != null) {
            testServer.stop(0);
        }
    }

    // --- scheme ---

    @Test
    void validate_httpsScheme_accepted() {
        URI uri = SsrfGuard.validate("https://8.8.8.8");
        assertThat(uri.getHost()).isEqualTo("8.8.8.8");
    }

    @Test
    void validate_httpScheme_rejectedByDefault() {
        assertThatThrownBy(() -> SsrfGuard.validate("http://8.8.8.8"))
            .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_httpScheme_acceptedWhenAllowHttpTrue() {
        URI uri = SsrfGuard.validate("http://8.8.8.8", true);
        assertThat(uri.getScheme()).isEqualTo("http");
    }

    @Test
    void validate_otherScheme_rejected() {
        assertThatThrownBy(() -> SsrfGuard.validate("ftp://8.8.8.8"))
            .isInstanceOf(SsrfBlockedException.class);
    }

    // --- port ---

    @Test
    void validate_nonStandardPort_rejected() {
        assertThatThrownBy(() -> SsrfGuard.validate("https://8.8.8.8:8443"))
            .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_explicitStandardPort_accepted() {
        SsrfGuard.validate("https://8.8.8.8:443");
    }

    // --- malformed / blank ---

    @Test
    void validate_blankUrl_rejected() {
        assertThatThrownBy(() -> SsrfGuard.validate("")).isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> SsrfGuard.validate((String) null)).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_malformedUrl_rejected() {
        assertThatThrownBy(() -> SsrfGuard.validate("https://[not-a-valid-host"))
            .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_unresolvableHost_rejected() {
        // "invalid" is an IANA/RFC 2606 special-use TLD guaranteed to never resolve — avoids
        // depending on any real domain's current DNS state.
        assertThatThrownBy(() -> SsrfGuard.validate("https://definitely-not-real.invalid"))
            .isInstanceOf(SsrfBlockedException.class);
    }

    // --- the exact ranges specified: 127/8, 10/8, 172.16/12, 192.168/16, 169.254/16,
    // 0.0.0.0/8, 100.64/10, ::1, fc00::/7, fe80::/10, IPv4-mapped IPv6 ---

    @Test
    void validate_loopback_rejected() {
        assertThatThrownBy(() -> SsrfGuard.validate("https://127.0.0.1")).isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("https://[::1]")).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_privateTenDotRange_rejected() {
        assertThatThrownBy(() -> SsrfGuard.validate("https://10.0.0.5")).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_privateOneSevenTwoRange_rejected() {
        assertThatThrownBy(() -> SsrfGuard.validate("https://172.16.5.5")).isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("https://172.31.255.255")).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_privateOneNineTwoRange_rejected() {
        assertThatThrownBy(() -> SsrfGuard.validate("https://192.168.1.1")).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_linkLocalMetadataAddress_rejected() {
        // 169.254.169.254 — the cloud-provider instance-metadata endpoint, the single most
        // consequential address for this check to actually block.
        assertThatThrownBy(() -> SsrfGuard.validate("https://169.254.169.254"))
            .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_anyLocalAddress_rejected() {
        assertThatThrownBy(() -> SsrfGuard.validate("https://0.0.0.0")).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_carrierGradeNatRange_rejected() {
        assertThatThrownBy(() -> SsrfGuard.validate("https://100.64.0.1")).isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("https://100.127.255.255")).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_carrierGradeNatBoundary_justOutsideRange_accepted() {
        // 100.128.0.0 is one address past the /10 (100.64.0.0-100.127.255.255) -- confirms the
        // range check isn't accidentally over-wide.
        SsrfGuard.validate("https://100.128.0.1");
    }

    @Test
    void validate_ipv6UniqueLocalRange_rejected() {
        assertThatThrownBy(() -> SsrfGuard.validate("https://[fc00::1]")).isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("https://[fd00::1]")).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_ipv6LinkLocalRange_rejected() {
        assertThatThrownBy(() -> SsrfGuard.validate("https://[fe80::1]")).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_ipv4MappedIpv6PrivateAddress_rejected() {
        // ::ffff:10.0.0.1 -- an IPv4-mapped IPv6 form smuggling a private IPv4 address.
        assertThatThrownBy(() -> SsrfGuard.validate("https://[::ffff:10.0.0.1]"))
            .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("https://[::ffff:169.254.169.254]"))
            .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void validate_ipv4MappedIpv6PublicAddress_accepted() {
        SsrfGuard.validate("https://[::ffff:8.8.8.8]");
    }

    // --- accepted public addresses ---

    @Test
    void validate_publicIpv4Address_accepted() {
        SsrfGuard.validate("https://8.8.8.8");
        SsrfGuard.validate("https://1.1.1.1");
    }

    // --- redirect handling: disabled, not re-validated per hop ---

    @Test
    void disableRedirects_doesNotFollowRedirectToAnotherEndpoint() throws Exception {
        AtomicInteger targetHits = new AtomicInteger(0);
        testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        testServer.createContext("/target", exchange -> {
            targetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        testServer.start();
        int port = testServer.getAddress().getPort();

        RestClient client = SsrfGuard.disableRedirects(RestClient.builder(), Duration.ofSeconds(3), Duration.ofSeconds(5)).build();
        var response = client.get()
            .uri("http://127.0.0.1:" + port + "/start")
            .retrieve()
            .toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(targetHits.get()).isZero();
    }

    // --- Step H2 (see STEP_REPORT.md): configured timeout actually bounds the call ---
    //
    // Proves disableRedirects' connect/read timeout parameters are real, against a real local
    // server, not a mock -- the exact mechanism JiraApiClient, ServiceNowApiClient, and
    // WebhookService all route their outbound call through. Doesn't go through
    // SsrfGuard.validate() first (same as the redirect test above) because validate() rejects
    // loopback addresses unconditionally -- this isolates the timeout mechanism itself from that
    // separate, already-covered check.
    @Test
    void disableRedirects_honorsConfiguredReadTimeout_failsFastInsteadOfHangingForever() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger(0);
        testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/hangs", exchange -> {
            requestsReceived.incrementAndGet();
            // Deliberately never call exchange.sendResponseHeaders/close -- simulates a hung
            // third party that accepted the TCP connection but never answers.
        });
        testServer.start();
        int port = testServer.getAddress().getPort();

        RestClient client = SsrfGuard.disableRedirects(RestClient.builder(), Duration.ofMillis(500), Duration.ofMillis(500)).build();

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.get()
            .uri("http://127.0.0.1:" + port + "/hangs")
            .retrieve()
            .toBodilessEntity())
            .isInstanceOf(Exception.class);
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(requestsReceived.get()).isEqualTo(1);
        // Configured read timeout is 500ms -- 5s is a generous upper bound that would still
        // catch a regression back to "no timeout" (which would instead hang far longer than
        // any real test suite would tolerate, rather than failing comfortably inside 5s the way
        // a real ~500ms timeout does).
        assertThat(elapsedMs).isLessThan(5000L);
    }
}
