package com.aetherops.integrations;

import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.repository.TenantTicketIntegrationRepository;
import com.aetherops.integrations.service.ServiceNowTicketService;
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
class ServiceNowTicketServiceTest {

    @Mock private TenantTicketIntegrationRepository integrationRepository;
    @Mock private TicketIntegrationTokenEncryptor tokenEncryptor;

    private RestClient mockRestClient;
    private RestClient.Builder builder;
    private ServiceNowTicketService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class);
        builder = mock(RestClient.Builder.class);
        service = new ServiceNowTicketService(integrationRepository, tokenEncryptor, new ObjectMapper(), builder);
    }

    private TenantTicketIntegration config() {
        return TenantTicketIntegration.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).provider(TicketProvider.SERVICENOW)
            .baseUrl("https://acme.service-now.com")
            .username("bot").encryptedApiToken("enc-token").enabled(true)
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
    void createIncidentTicket_noConfiguredIntegration_returnsEmptyWithoutThrowing() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.empty());

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).isEmpty();
        verifyNoInteractions(tokenEncryptor);
    }

    @Test
    void createIncidentTicket_disabledIntegration_returnsEmpty() {
        TenantTicketIntegration disabled = config();
        disabled.setEnabled(false);
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.of(disabled));

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).isEmpty();
    }

    @Test
    void createIncidentTicket_successfulResponse_returnsParsedSysId() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        stubPostChain("{\"result\": {\"sys_id\": \"abc123def456\"}}");

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).contains("abc123def456");
    }

    @Test
    void createIncidentTicket_responseWithoutSysId_returnsEmpty() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        stubPostChain("{\"error\": \"unauthorized\"}");

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).isEmpty();
    }

    @Test
    void createIncidentTicket_restClientThrows_returnsEmptyWithoutPropagating() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        when(builder.build()).thenReturn(mockRestClient);
        when(mockRestClient.post()).thenThrow(new RuntimeException("network error"));

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).isEmpty();
    }
}
