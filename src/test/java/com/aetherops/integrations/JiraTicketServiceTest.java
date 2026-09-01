package com.aetherops.integrations;

import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.repository.TenantTicketIntegrationRepository;
import com.aetherops.integrations.service.JiraTicketService;
import com.aetherops.integrations.service.TicketIntegrationTokenEncryptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JiraTicketServiceTest {

    @Mock private TenantTicketIntegrationRepository integrationRepository;
    @Mock private TicketIntegrationTokenEncryptor tokenEncryptor;

    private RestClient mockRestClient;
    private RestClient.Builder builder;
    private JiraTicketService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class);
        builder = mock(RestClient.Builder.class);
        service = new JiraTicketService(integrationRepository, tokenEncryptor, new ObjectMapper(), builder);
    }

    private TenantTicketIntegration config() {
        return TenantTicketIntegration.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).provider(TicketProvider.JIRA)
            .baseUrl("https://acme.atlassian.net").projectKey("OPS")
            .username("bot@acme.com").encryptedApiToken("enc-token").enabled(true)
            .build();
    }

    @SuppressWarnings("unchecked")
    private RestClient.RequestBodyUriSpec stubPostChain(String responseJson) {
        lenient().when(builder.build()).thenReturn(mockRestClient);
        RestClient.RequestBodyUriSpec bodySpec = mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(responseJson);
        when(mockRestClient.post()).thenReturn(bodySpec);
        return bodySpec;
    }

    @Test
    void createIssue_noConfiguredIntegration_returnsEmptyWithoutThrowing() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.empty());

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).isEmpty();
        verifyNoInteractions(tokenEncryptor);
    }

    @Test
    void createIssue_disabledIntegration_returnsEmpty() {
        TenantTicketIntegration disabled = config();
        disabled.setEnabled(false);
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.of(disabled));

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).isEmpty();
    }

    @Test
    void createIssue_successfulResponse_returnsParsedIssueKey() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        stubPostChain("{\"key\": \"OPS-123\"}");

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).contains("OPS-123");
    }

    @Test
    void createIssue_responseWithoutKey_returnsEmpty() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        stubPostChain("{\"errorMessages\": [\"bad request\"]}");

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).isEmpty();
    }

    @Test
    void createIssue_restClientThrows_returnsEmptyWithoutPropagating() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        when(builder.build()).thenReturn(mockRestClient);
        when(mockRestClient.post()).thenThrow(new RuntimeException("network error"));

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).isEmpty();
    }
}
