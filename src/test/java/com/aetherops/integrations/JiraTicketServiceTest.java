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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        // SsrfGuard.disableRedirects(builder) calls builder.requestFactory(...), which a real
        // RestClient.Builder returns `this` from (fluent/mutating) -- mirror that so the
        // subsequent .build() still hits the stub below.
        lenient().when(builder.requestFactory(any())).thenReturn(builder);
        service = new JiraTicketService(integrationRepository, tokenEncryptor, new ObjectMapper(), builder);
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

    // --- SSRF guard (Phase 2 remediation, finding 4 — see STEP_REPORT.md) ---

    private TenantTicketIntegration configWithBaseUrl(String baseUrl) {
        TenantTicketIntegration config = config();
        config.setBaseUrl(baseUrl);
        return config;
    }

    @Test
    void createIssue_privateRangeBaseUrl_rejectedWithoutCallingRestClientOrDecryptingToken() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA))
            .thenReturn(Optional.of(configWithBaseUrl("https://10.0.0.5")));

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).isEmpty();
        verifyNoInteractions(tokenEncryptor);
        verifyNoInteractions(builder);
    }

    @Test
    void createIssue_linkLocalMetadataBaseUrl_rejected() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA))
            .thenReturn(Optional.of(configWithBaseUrl("https://169.254.169.254")));

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).isEmpty();
        verifyNoInteractions(builder);
    }

    @Test
    void createIssue_httpBaseUrl_rejected() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA))
            .thenReturn(Optional.of(configWithBaseUrl("http://8.8.8.8")));

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).isEmpty();
        verifyNoInteractions(builder);
    }

    @Test
    void createIssue_validPublicHttpsBaseUrl_accepted() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        stubPostChain("{\"key\": \"OPS-999\"}");

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).contains("OPS-999");
    }

    @Test
    void createIssue_redirectResponse_rejectedNotFollowed() {
        when(integrationRepository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.of(config()));
        when(tokenEncryptor.decrypt("enc-token")).thenReturn("plain-token");
        stubPostChain(ResponseEntity.status(HttpStatus.FOUND).header("Location", "http://169.254.169.254/").build());

        Optional<String> result = service.createIssue(tenantId, incidentId, "summary", "description");

        assertThat(result).isEmpty();
    }
}
