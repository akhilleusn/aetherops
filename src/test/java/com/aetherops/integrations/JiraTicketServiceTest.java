package com.aetherops.integrations;

import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.repository.TenantTicketIntegrationRepository;
import com.aetherops.integrations.service.JiraApiClient;
import com.aetherops.integrations.service.JiraTicketService;
import com.aetherops.integrations.service.TicketIntegrationTokenEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Step H2 (see STEP_REPORT.md): JiraTicketService is now pure orchestration -- tenant-config
 * lookup, then a bounded wait on {@link JiraApiClient}'s future. The actual HTTP call, SSRF
 * validation, redirect handling and JSON parsing moved to {@link JiraApiClient} (see
 * JiraApiClientTest) since Spring AOP proxies (which {@code @CircuitBreaker}/{@code @Bulkhead}
 * rely on) don't intercept same-class self-invocation -- see JiraApiClient's javadoc.
 */
@ExtendWith(MockitoExtension.class)
class JiraTicketServiceTest {

    @Mock private TenantTicketIntegrationRepository integrationRepository;
    @Mock private TicketIntegrationTokenEncryptor tokenEncryptor;
    @Mock private JiraApiClient apiClient;

    private JiraTicketService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new JiraTicketService(integrationRepository, tokenEncryptor, apiClient, 5000L);
    }

    private TenantTicketIntegration config() {
        return TenantTicketIntegration.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).provider(TicketProvider.JIRA)
            .baseUrl("https://8.8.8.8").projectKey("OPS")
            .username("bot@acme.com").encryptedApiToken("enc-token").enabled(true)
            .build();
    }

    @Test
    void createIssue_noConfiguredIntegration_returnsEmptyWithoutThrowing() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.empty());

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).isEmpty();
        verifyNoInteractions(tokenEncryptor, apiClient);
    }

    @Test
    void createIssue_disabledIntegration_returnsEmpty() {
        TenantTicketIntegration disabled = config();
        disabled.setEnabled(false);
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.of(disabled));

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).isEmpty();
        verifyNoInteractions(apiClient);
    }

    @Test
    void createIssue_apiClientSucceeds_returnsIssueKey() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        when(apiClient.createIssue(eq(tenantId), eq(incidentId), any(), any(), any(), eq("plain-token")))
            .thenReturn(CompletableFuture.completedFuture(Optional.of("OPS-123")));

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).contains("OPS-123");
    }

    @Test
    void createIssue_apiClientFutureFailsExceptionally_returnsEmptyWithoutPropagating() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        CompletableFuture<Optional<String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("connection refused"));
        when(apiClient.createIssue(any(), any(), any(), any(), any(), any())).thenReturn(failed);

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).isEmpty();
    }

    // Step H2: proves the bounded wait itself -- a future that never completes (simulating the
    // bulkhead pool being saturated, or a call that somehow outlasts its own connect/read
    // timeout) does not hang JiraTicketService's caller forever; it gives up at
    // app.integrations.jira.call-timeout-ms and returns empty, same as any other failure.
    @Test
    void createIssue_apiClientFutureNeverCompletes_givesUpAtCallTimeout_returnsEmpty() {
        service = new JiraTicketService(integrationRepository, tokenEncryptor, apiClient, 200L);
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        when(apiClient.createIssue(any(), any(), any(), any(), any(), any())).thenReturn(new CompletableFuture<>());

        long start = System.currentTimeMillis();
        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(result).isEmpty();
        assertThat(elapsedMs).isLessThan(5000L);
    }
}
