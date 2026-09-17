package com.aetherops.integrations;

import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.service.JiraApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Step H2 (see STEP_REPORT.md): the actual Jira HTTP call -- SSRF validation, redirect
 * handling, and JSON parsing. See JiraTicketServiceTest for the orchestration-level tests
 * (config lookup, bounded-wait timeout), and SsrfGuardTest for the real-timeout test against a
 * local server (this class's baseUrl always goes through SsrfGuard.validate() first, which
 * rejects loopback addresses unconditionally -- see this file's last test for why that test
 * lives there instead).
 */
@ExtendWith(MockitoExtension.class)
class JiraApiClientTest {

    private RestClient mockRestClient;
    private RestClient.Builder builder;
    private JiraApiClient client;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class);
        builder = mock(RestClient.Builder.class);
        // SsrfGuard.disableRedirects(builder, ...) calls builder.requestFactory(...), which a
        // real RestClient.Builder returns `this` from (fluent/mutating) -- mirror that so the
        // subsequent .build() still hits the stub below.
        lenient().when(builder.requestFactory(any())).thenReturn(builder);
        client = new JiraApiClient(new ObjectMapper(), builder, 3000L, 5000L);
    }

    // 8.8.8.8 (Google public DNS) is used as a stable, always-public IP literal rather than a
    // hostname like "atlassian.net" specifically so these tests never depend on real DNS
    // resolution -- SsrfGuard.validate() now genuinely resolves whatever host is given it.
    private TenantTicketIntegration config() {
        return TenantTicketIntegration.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).provider(TicketProvider.JIRA)
            .baseUrl("https://8.8.8.8").projectKey("OPS")
            .username("bot@acme.com").encryptedApiToken("enc-token").enabled(true)
            .build();
    }

    @SuppressWarnings("unchecked")
    private RestClient.RequestBodyUriSpec stubPostChain(String responseJson) {
        return stubPostChain(ResponseEntity.ok(responseJson));
    }

    @SuppressWarnings("unchecked")
    private RestClient.RequestBodyUriSpec stubPostChain(ResponseEntity<String> response) {
        lenient().when(builder.build()).thenReturn(mockRestClient);
        RestClient.RequestBodyUriSpec bodySpec = mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(String.class)).thenReturn(response);
        when(mockRestClient.post()).thenReturn(bodySpec);
        return bodySpec;
    }

    private Optional<String> await(CompletableFuture<Optional<String>> future) throws Exception {
        return future.get(2, TimeUnit.SECONDS);
    }

    @Test
    void createIssue_successfulResponse_returnsParsedIssueKey() throws Exception {
        stubPostChain("{\"key\": \"OPS-123\"}");

        Optional<String> result = await(client.createIssue(tenantId, incidentId, "summary", "description", config(), "plain-token"));

        assertThat(result).contains("OPS-123");
    }

    @Test
    void createIssue_responseWithoutKey_returnsEmpty() throws Exception {
        stubPostChain("{\"errorMessages\": [\"bad request\"]}");

        Optional<String> result = await(client.createIssue(tenantId, incidentId, "summary", "description", config(), "plain-token"));

        assertThat(result).isEmpty();
    }

    // Step H2: this is the behavior change from before the refactor -- a real call failure now
    // propagates as an exceptionally-completed future instead of being swallowed into
    // Optional.empty() here. A circuit breaker that never sees a failure can never trip (see
    // this class's javadoc); JiraTicketService is the layer that catches and converts to empty.
    // Step H2: in production this exception surfaces via a failed CompletableFuture -- the
    // resilience4j ThreadPoolBulkhead AOP proxy is what submits this method's body to its own
    // pool and wraps a synchronous throw into an exceptionally-completed future. This is a plain
    // unit test with no Spring context, so no proxy is involved -- the method body runs directly
    // and a synchronous throw is a synchronous throw. What this test actually proves is the part
    // that doesn't depend on the proxy: the method body itself does not catch-and-swallow the
    // failure into Optional.empty() the way the OLD (pre-H2) JiraTicketService used to -- it lets
    // the exception out, which is what makes it observable to whichever layer (proxy in
    // production, direct caller here) is watching for a failure to occur.
    @Test
    void createIssue_restClientThrows_doesNotSwallowIntoEmptyOptional() {
        when(builder.build()).thenReturn(mockRestClient);
        when(mockRestClient.post()).thenThrow(new RuntimeException("network error"));

        assertThatThrownBy(() -> client.createIssue(tenantId, incidentId, "summary", "description", config(), "plain-token"))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("network error");
    }

    // --- SSRF guard (Phase 2 remediation, finding 4 — see STEP_REPORT.md) ---

    private TenantTicketIntegration configWithBaseUrl(String baseUrl) {
        TenantTicketIntegration config = config();
        config.setBaseUrl(baseUrl);
        return config;
    }

    @Test
    void createIssue_privateRangeBaseUrl_rejectedWithoutCallingRestClient() {
        assertThatThrownBy(() ->
            client.createIssue(tenantId, incidentId, "summary", "description", configWithBaseUrl("https://10.0.0.5"), "plain-token"))
            .isInstanceOf(com.aetherops.common.exception.SsrfBlockedException.class);
        verifyNoInteractions(builder);
    }

    @Test
    void createIssue_redirectResponse_rejectedNotFollowed() throws Exception {
        stubPostChain(ResponseEntity.status(HttpStatus.FOUND).header("Location", "http://169.254.169.254/").build());

        Optional<String> result = await(client.createIssue(tenantId, incidentId, "summary", "description", config(), "plain-token"));

        assertThat(result).isEmpty();
    }

    // A real hanging-server timeout test (proving the configured connect/read timeout actually
    // bounds a call) lives in SsrfGuardTest, not here: SsrfGuard.validate() -- which this
    // client's config baseUrl always goes through first -- rejects loopback addresses
    // unconditionally (see SsrfGuardTest's own loopback-rejection tests), so a local test server
    // on 127.0.0.1 can never be reached through this class's public createIssue() method
    // regardless of TLS. SsrfGuardTest.disableRedirects_honorsConfiguredReadTimeout_failsFast
    // exercises the exact same disableRedirects(...) construction this class uses, directly,
    // without going through validate() first.
}
