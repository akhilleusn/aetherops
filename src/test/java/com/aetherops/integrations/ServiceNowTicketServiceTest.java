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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        lenient().when(builder.requestFactory(any())).thenReturn(builder);
        service = new ServiceNowTicketService(integrationRepository, tokenEncryptor, new ObjectMapper(), builder);
    }

    // 8.8.8.8 (Google public DNS) is used as a stable, always-public IP literal rather than a
    // hostname like "service-now.com" specifically so these tests never depend on real DNS
    // resolution -- SsrfGuard.validate() now genuinely resolves whatever host is given it.
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

    // --- SSRF guard (Phase 2 remediation, finding 4 — see STEP_REPORT.md) ---

    private TenantTicketIntegration configWithBaseUrl(String baseUrl) {
        TenantTicketIntegration config = config();
        config.setBaseUrl(baseUrl);
        return config;
    }

    @Test
    void createIncidentTicket_privateRangeBaseUrl_rejectedWithoutCallingRestClientOrDecryptingToken() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW))
            .thenReturn(Optional.of(configWithBaseUrl("https://10.0.0.5")));

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).isEmpty();
        verifyNoInteractions(tokenEncryptor);
        verifyNoInteractions(builder);
    }

    @Test
    void createIncidentTicket_linkLocalMetadataBaseUrl_rejected() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW))
            .thenReturn(Optional.of(configWithBaseUrl("https://169.254.169.254")));

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).isEmpty();
        verifyNoInteractions(builder);
    }

    @Test
    void createIncidentTicket_httpBaseUrl_rejected() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW))
            .thenReturn(Optional.of(configWithBaseUrl("http://8.8.8.8")));

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).isEmpty();
        verifyNoInteractions(builder);
    }

    @Test
    void createIncidentTicket_validPublicHttpsBaseUrl_accepted() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        stubPostChain("{\"result\": {\"sys_id\": \"zzz999\"}}");

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).contains("zzz999");
    }

    @Test
    void createIncidentTicket_redirectResponse_rejectedNotFollowed() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        stubPostChain(ResponseEntity.status(HttpStatus.FOUND).header("Location", "http://169.254.169.254/").build());

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).isEmpty();
    }
}
