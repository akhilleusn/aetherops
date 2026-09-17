package com.aetherops.integrations;

import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.service.ServiceNowApiClient;
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
 * Step H2 (see STEP_REPORT.md): the actual ServiceNow HTTP call. See ServiceNowTicketServiceTest
 * for orchestration-level tests, and JiraApiClientTest (same shape) for the real-timeout test
 * against a local server -- not duplicated here to keep this file focused; the timeout mechanism
 * (SsrfGuard.disableRedirects + JdkClientHttpRequestFactory) is identical code shared by both.
 */
@ExtendWith(MockitoExtension.class)
class ServiceNowApiClientTest {

    private RestClient mockRestClient;
    private RestClient.Builder builder;
    private ServiceNowApiClient client;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class);
        builder = mock(RestClient.Builder.class);
        lenient().when(builder.requestFactory(any())).thenReturn(builder);
        client = new ServiceNowApiClient(new ObjectMapper(), builder, 3000L, 5000L);
    }

    private TenantTicketIntegration config() {
        return TenantTicketIntegration.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).provider(TicketProvider.SERVICENOW)
            .baseUrl("https://8.8.8.8")
            .username("bot").encryptedApiToken("enc-token").enabled(true)
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
    void createIncidentTicket_successfulResponse_returnsParsedSysId() throws Exception {
        stubPostChain("{\"result\": {\"sys_id\": \"abc123def456\"}}");

        Optional<String> result = await(client.createIncidentTicket(tenantId, incidentId, "short desc", "full desc", config(), "plain-token"));

        assertThat(result).contains("abc123def456");
    }

    @Test
    void createIncidentTicket_responseWithoutSysId_returnsEmpty() throws Exception {
        stubPostChain("{\"error\": \"unauthorized\"}");

        Optional<String> result = await(client.createIncidentTicket(tenantId, incidentId, "short desc", "full desc", config(), "plain-token"));

        assertThat(result).isEmpty();
    }

    // See JiraApiClientTest's equivalent test for why this asserts a synchronous throw rather
    // than a failed future -- no Spring/resilience4j AOP proxy is involved in a plain unit test.
    @Test
    void createIncidentTicket_restClientThrows_doesNotSwallowIntoEmptyOptional() {
        when(builder.build()).thenReturn(mockRestClient);
        when(mockRestClient.post()).thenThrow(new RuntimeException("network error"));

        assertThatThrownBy(() -> client.createIncidentTicket(tenantId, incidentId, "short desc", "full desc", config(), "plain-token"))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("network error");
    }

    private TenantTicketIntegration configWithBaseUrl(String baseUrl) {
        TenantTicketIntegration config = config();
        config.setBaseUrl(baseUrl);
        return config;
    }

    @Test
    void createIncidentTicket_privateRangeBaseUrl_rejectedWithoutCallingRestClient() {
        assertThatThrownBy(() ->
            client.createIncidentTicket(tenantId, incidentId, "short desc", "full desc", configWithBaseUrl("https://10.0.0.5"), "plain-token"))
            .isInstanceOf(com.aetherops.common.exception.SsrfBlockedException.class);
        verifyNoInteractions(builder);
    }

    @Test
    void createIncidentTicket_redirectResponse_rejectedNotFollowed() throws Exception {
        stubPostChain(ResponseEntity.status(HttpStatus.FOUND).header("Location", "http://169.254.169.254/").build());

        Optional<String> result = await(client.createIncidentTicket(tenantId, incidentId, "short desc", "full desc", config(), "plain-token"));

        assertThat(result).isEmpty();
    }
}
