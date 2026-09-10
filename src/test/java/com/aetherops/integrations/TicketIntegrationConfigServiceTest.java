package com.aetherops.integrations;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.integrations.model.ConfigureTicketIntegrationRequest;
import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketIntegrationResponse;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.repository.TenantTicketIntegrationRepository;
import com.aetherops.common.exception.SsrfBlockedException;
import com.aetherops.integrations.service.TicketIntegrationConfigService;
import com.aetherops.integrations.service.TicketIntegrationTokenEncryptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketIntegrationConfigServiceTest {

    @Mock private TenantTicketIntegrationRepository repository;
    @Mock private TicketIntegrationTokenEncryptor encryptor;

    @InjectMocks
    private TicketIntegrationConfigService configService;

    private final UUID tenantId = UUID.randomUUID();

    // 8.8.8.8 (Google public DNS) is used as a stable, always-public IP literal rather than a
    // hostname like "atlassian.net" specifically so these tests never depend on real DNS
    // resolution -- SsrfGuard.validate() now genuinely resolves whatever host is given it.
    @Test
    void configure_newIntegration_encryptsTokenAndNeverReturnsIt() {
        when(repository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.empty());
        when(encryptor.encrypt("plain-token")).thenReturn("encrypted-token");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        ConfigureTicketIntegrationRequest request = new ConfigureTicketIntegrationRequest(
            TicketProvider.JIRA, "https://8.8.8.8", "OPS", "bot@acme.com", "plain-token");

        TicketIntegrationResponse response = configService.configure(tenantId, request);

        assertThat(response.getBaseUrl()).isEqualTo("https://8.8.8.8");
        assertThat(response.isEnabled()).isTrue();
        // TicketIntegrationResponse has no token field at all — the type itself guarantees non-leakage,
        // but assert the persisted entity used the encrypted value, not the plaintext.
        ArgumentCaptor<TenantTicketIntegration> captor = ArgumentCaptor.forClass(TenantTicketIntegration.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEncryptedApiToken()).isEqualTo("encrypted-token");
    }

    @Test
    void configure_existingIntegration_updatesInPlace() {
        TenantTicketIntegration existing = TenantTicketIntegration.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).provider(TicketProvider.JIRA)
            .baseUrl("https://8.8.8.8").enabled(true).build();
        when(repository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.of(existing));
        when(encryptor.encrypt(any())).thenReturn("encrypted-token");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        ConfigureTicketIntegrationRequest request = new ConfigureTicketIntegrationRequest(
            TicketProvider.JIRA, "https://1.1.1.1", "OPS", "bot@acme.com", "new-token");

        TicketIntegrationResponse response = configService.configure(tenantId, request);

        assertThat(response.getBaseUrl()).isEqualTo("https://1.1.1.1");
        ArgumentCaptor<TenantTicketIntegration> captor = ArgumentCaptor.forClass(TenantTicketIntegration.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
    }

    // --- SSRF guard (Phase 2 remediation, finding 4 — see STEP_REPORT.md) ---

    @Test
    void configure_privateRangeBaseUrl_rejectedWithoutSaving() {
        ConfigureTicketIntegrationRequest request = new ConfigureTicketIntegrationRequest(
            TicketProvider.JIRA, "https://10.0.0.5", "OPS", "bot@acme.com", "plain-token");

        assertThatThrownBy(() -> configService.configure(tenantId, request))
            .isInstanceOf(SsrfBlockedException.class);
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void configure_linkLocalMetadataBaseUrl_rejected() {
        ConfigureTicketIntegrationRequest request = new ConfigureTicketIntegrationRequest(
            TicketProvider.JIRA, "https://169.254.169.254", "OPS", "bot@acme.com", "plain-token");

        assertThatThrownBy(() -> configService.configure(tenantId, request))
            .isInstanceOf(SsrfBlockedException.class);
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void configure_httpBaseUrl_rejected() {
        ConfigureTicketIntegrationRequest request = new ConfigureTicketIntegrationRequest(
            TicketProvider.JIRA, "http://8.8.8.8", "OPS", "bot@acme.com", "plain-token");

        assertThatThrownBy(() -> configService.configure(tenantId, request))
            .isInstanceOf(SsrfBlockedException.class);
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void configure_validPublicHttpsBaseUrl_accepted() {
        when(repository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.empty());
        when(encryptor.encrypt(any())).thenReturn("encrypted-token");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        ConfigureTicketIntegrationRequest request = new ConfigureTicketIntegrationRequest(
            TicketProvider.SERVICENOW, "https://8.8.8.8", null, "bot", "plain-token");

        TicketIntegrationResponse response = configService.configure(tenantId, request);

        assertThat(response.getBaseUrl()).isEqualTo("https://8.8.8.8");
    }

    @Test
    void get_unconfiguredProvider_throwsResourceNotFound() {
        when(repository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> configService.get(tenantId, TicketProvider.SERVICENOW))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void get_configuredProvider_returnsResponseWithoutToken() {
        TenantTicketIntegration existing = TenantTicketIntegration.builder()
            .tenantId(tenantId).provider(TicketProvider.SERVICENOW)
            .baseUrl("https://acme.service-now.com").encryptedApiToken("secret").enabled(true).build();
        when(repository.findByTenantIdAndProvider(tenantId, TicketProvider.SERVICENOW)).thenReturn(Optional.of(existing));

        TicketIntegrationResponse response = configService.get(tenantId, TicketProvider.SERVICENOW);

        assertThat(response.getBaseUrl()).isEqualTo("https://acme.service-now.com");
    }

    @Test
    void delete_existingIntegration_removesIt() {
        TenantTicketIntegration existing = TenantTicketIntegration.builder()
            .tenantId(tenantId).provider(TicketProvider.JIRA).build();
        when(repository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.of(existing));

        configService.delete(tenantId, TicketProvider.JIRA);

        verify(repository).delete(existing);
    }

    @Test
    void delete_noExistingIntegration_isNoOp() {
        when(repository.findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)).thenReturn(Optional.empty());

        configService.delete(tenantId, TicketProvider.JIRA);

        verify(repository, org.mockito.Mockito.never()).delete(any());
    }
}
