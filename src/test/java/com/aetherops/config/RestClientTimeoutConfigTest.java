package com.aetherops.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step H2 (see STEP_REPORT.md): {@link RestClientTimeoutConfig}'s {@link RestClientCustomizer}
 * bean is what gives every default-built {@code RestClient.Builder} in this app (Slack, calendar,
 * push/Expo, the Anthropic health check, AWS SNS cert/subscription-confirm calls) a bounded
 * connect/read timeout -- Jira, ServiceNow, and webhook delivery use a different, separately-
 * tested mechanism ({@code SsrfGuard.disableRedirects}, see SsrfGuardTest) since they need to
 * disable redirects too. This proves the customizer bean itself produces a factory that actually
 * bounds a call, against a real local server, not a mock.
 */
class RestClientTimeoutConfigTest {

    private HttpServer testServer;

    @AfterEach
    void stopServer() {
        if (testServer != null) {
            testServer.stop(0);
        }
    }

    @Test
    void customizedRestClientBuilder_honorsConfiguredReadTimeout_failsFastInsteadOfHangingForever() throws Exception {
        AtomicInteger requestsReceived = new AtomicInteger(0);
        testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/hangs", exchange -> {
            requestsReceived.incrementAndGet();
            // Deliberately never respond -- simulates a hung Slack/calendar/push/etc. endpoint.
        });
        testServer.start();
        int port = testServer.getAddress().getPort();

        RestClientTimeoutConfig config = new RestClientTimeoutConfig();
        RestClientCustomizer customizer = config.defaultRestClientTimeoutCustomizer(500L, 500L);
        RestClient.Builder builder = RestClient.builder();
        customizer.customize(builder);
        RestClient client = builder.build();

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.get()
            .uri("http://127.0.0.1:" + port + "/hangs")
            .retrieve()
            .toBodilessEntity())
            .isInstanceOf(Exception.class);
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(requestsReceived.get()).isEqualTo(1);
        // Configured read timeout is 500ms -- 5s is a generous upper bound that would still
        // catch a regression back to "no timeout" (unbounded default), rather than failing
        // comfortably inside 5s the way a real ~500ms timeout does.
        assertThat(elapsedMs).isLessThan(5000L);
    }
}
