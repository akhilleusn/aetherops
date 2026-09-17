package com.aetherops.integrations;

import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.repository.TenantTicketIntegrationRepository;
import com.aetherops.integrations.service.ServiceNowApiClient;
import com.aetherops.integrations.service.ServiceNowTicketService;
import com.aetherops.integrations.service.TicketIntegrationTokenEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Step H2 (see STEP_REPORT.md): ServiceNowTicketService is now pure orchestration -- see
 * JiraTicketServiceTest's javadoc, this mirrors it exactly. The actual HTTP call moved to
 * {@link ServiceNowApiClient} (see ServiceNowApiClientTest).
 */
@ExtendWith(MockitoExtension.class)
class ServiceNowTicketServiceTest {

    @Mock private TenantTicketIntegrationRepository integrationRepository;
    @Mock private TicketIntegrationTokenEncryptor tokenEncryptor;
    @Mock private ServiceNowApiClient apiClient;

    private ServiceNowTicketService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ServiceNowTicketService(integrationRepository, tokenEncryptor, apiClient, 5000L);
    }

    private TenantTicketIntegration config() {
        return TenantTicketIntegration.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).provider(TicketProvider.SERVICENOW)
            .baseUrl("https://8.8.8.8")
            .username("bot").encryptedApiToken("enc-token").enabled(true)
            .build();
    }

    @Test
    void createIncidentTicket_noConfiguredIntegration_returnsEmptyWithoutThrowing() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.empty());

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).isEmpty();
        verifyNoInteractions(tokenEncryptor, apiClient);
    }

    @Test
    void createIncidentTicket_disabledIntegration_returnsEmpty() {
        TenantTicketIntegration disabled = config();
        disabled.setEnabled(false);
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.of(disabled));

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).isEmpty();
        verifyNoInteractions(apiClient);
    }

    @Test
    void createIncidentTicket_apiClientSucceeds_returnsSysId() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        when(apiClient.createIncidentTicket(eq(tenantId), eq(incidentId), any(), any(), any(), eq("plain-token")))
            .thenReturn(CompletableFuture.completedFuture(Optional.of("abc123def456")));

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).contains("abc123def456");
    }

    @Test
    void createIncidentTicket_apiClientFutureFailsExceptionally_returnsEmptyWithoutPropagating() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        CompletableFuture<Optional<String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("connection refused"));
        when(apiClient.createIncidentTicket(any(), any(), any(), any(), any(), any())).thenReturn(failed);

        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");

        assertThat(result).isEmpty();
    }

    @Test
    void createIncidentTicket_apiClientFutureNeverCompletes_givesUpAtCallTimeout_returnsEmpty() {
        service = new ServiceNowTicketService(integrationRepository, tokenEncryptor, apiClient, 200L);
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        when(apiClient.createIncidentTicket(any(), any(), any(), any(), any(), any())).thenReturn(new CompletableFuture<>());

        long start = System.currentTimeMillis();
        Optional<String> result = service.createIncidentTicket(tenantId, incidentId, "short desc", "full desc");
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(result).isEmpty();
        assertThat(elapsedMs).isLessThan(5000L);
    }
}
