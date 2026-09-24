package com.aetherops.analysis.config;

import com.aetherops.config.RestClientTimeoutConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Anthropic client must use its own read timeout ({@code app.anthropic.read-timeout-ms}),
 * not the shared 3s default {@link RestClientTimeoutConfig} applies to every builder -- a real
 * Messages response always takes longer than that. Against a real local server, not a mock.
 */
class AIProviderConfigTest {

    private static final long SHARED_READ_TIMEOUT_MS = 300;
    private static final long SERVER_DELAY_MS        = 1000;

    private HttpServer testServer;
    private String     slowUrl;

    @BeforeEach
    void startServer() throws Exception {
        testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/slow", exchange -> {
            try {
                Thread.sleep(SERVER_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        testServer.start();
        slowUrl = "http://127.0.0.1:" + testServer.getAddress().getPort() + "/slow";
    }

    @AfterEach
    void stopServer() {
        testServer.stop(0);
    }

    // A response slower than the shared default but inside the Anthropic timeout must succeed
    @Test
    void anthropicRestClient_overridesSharedReadTimeout() {
        RestClient client = AIProviderConfig.anthropicRestClient(
            sharedDefaultBuilder(), 2000, SERVER_DELAY_MS * 3);

        assertThat(client.get().uri(slowUrl).retrieve().body(String.class)).isEqualTo("ok");
    }

    // The Anthropic timeout is still a real bound -- a slower response fails instead of hanging
    @Test
    void anthropicRestClient_stillFailsWhenSlowerThanItsOwnTimeout() {
        RestClient client = AIProviderConfig.anthropicRestClient(
            sharedDefaultBuilder(), 2000, SERVER_DELAY_MS / 4);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.get().uri(slowUrl).retrieve().body(String.class))
            .isInstanceOf(Exception.class);
        assertThat(System.currentTimeMillis() - start).isLessThan(SERVER_DELAY_MS);
    }

    // What Spring Boot hands AIProviderConfig: a builder already customized with the shared default
    private RestClient.Builder sharedDefaultBuilder() {
        RestClient.Builder builder = RestClient.builder();
        new RestClientTimeoutConfig()
            .defaultRestClientTimeoutCustomizer(2000, SHARED_READ_TIMEOUT_MS)
            .customize(builder);
        return builder;
    }
}
