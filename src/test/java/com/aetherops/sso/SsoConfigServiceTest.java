package com.aetherops.sso;

import com.aetherops.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SsoConfigServiceTest {

    @Mock private TenantSsoConfigRepository ssoConfigRepository;

    @InjectMocks
    private SsoConfigService ssoConfigService;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(ssoConfigService, "baseUrl", "https://app.aetherops.dev");
        ReflectionTestUtils.setField(ssoConfigService, "spEntityId", "aetherops");
    }

    @Test
    void configure_newTenant_createsConfig() {
        when(ssoConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(ssoConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ConfigureSsoRequest request = new ConfigureSsoRequest(
            "https://idp.example.com/entity", "https://idp.example.com/sso", "-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----", true);
        TenantSsoConfigResponse response = ssoConfigService.configure(tenantId, request);

        assertThat(response.getTenantId()).isEqualTo(tenantId);
        assertThat(response.isEnabled()).isTrue();
        assertThat(response.getIdpEntityId()).isEqualTo("https://idp.example.com/entity");
    }

    @Test
    void configure_returnsCorrectAcsUrlUsingTenantIdAsRegistrationId() {
        when(ssoConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(ssoConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ConfigureSsoRequest request = new ConfigureSsoRequest("idp", "https://idp/sso", "cert", true);
        TenantSsoConfigResponse response = ssoConfigService.configure(tenantId, request);

        assertThat(response.getAcsUrl()).isEqualTo("https://app.aetherops.dev/login/saml2/sso/" + tenantId);
        assertThat(response.getSpEntityId()).isEqualTo("aetherops");
    }

    @Test
    void get_unconfiguredTenant_throwsResourceNotFound() {
        when(ssoConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ssoConfigService.get(tenantId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void get_configuredTenant_returnsIt() {
        TenantSsoConfig config = TenantSsoConfig.builder()
            .tenantId(tenantId).enabled(true)
            .idpEntityId("idp").idpSsoUrl("https://idp/sso").idpCertificate("cert")
            .build();
        when(ssoConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(config));

        TenantSsoConfigResponse response = ssoConfigService.get(tenantId);

        assertThat(response.isEnabled()).isTrue();
    }

    @Test
    void configure_existingTenant_updatesFieldsInPlace() {
        TenantSsoConfig existing = TenantSsoConfig.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).enabled(true)
            .idpEntityId("old-entity").idpSsoUrl("https://old-idp/sso").idpCertificate("old-cert").build();
        when(ssoConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(existing));
        when(ssoConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ConfigureSsoRequest request = new ConfigureSsoRequest("new-entity", "https://new-idp/sso", "new-cert", true);
        TenantSsoConfigResponse response = ssoConfigService.configure(tenantId, request);

        assertThat(response.getIdpEntityId()).isEqualTo("new-entity");
        assertThat(response.getIdpSsoUrl()).isEqualTo("https://new-idp/sso");
    }

    @Test
    void configure_disablingAnEnabledConfig_persistsDisabledState() {
        TenantSsoConfig existing = TenantSsoConfig.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).enabled(true)
            .idpEntityId("entity").idpSsoUrl("https://idp/sso").idpCertificate("cert").build();
        when(ssoConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(existing));
        when(ssoConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ConfigureSsoRequest request = new ConfigureSsoRequest("entity", "https://idp/sso", "cert", false);
        TenantSsoConfigResponse response = ssoConfigService.configure(tenantId, request);

        assertThat(response.isEnabled()).isFalse();
    }

    @Test
    void configure_acsUrlEmbedsTenantIdRegardlessOfBaseUrlTrailingSlash() {
        ReflectionTestUtils.setField(ssoConfigService, "baseUrl", "https://app.aetherops.dev");
        when(ssoConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(ssoConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        TenantSsoConfigResponse response = ssoConfigService.configure(tenantId,
            new ConfigureSsoRequest("idp", "https://idp/sso", "cert", true));

        assertThat(response.getAcsUrl()).endsWith("/login/saml2/sso/" + tenantId);
    }
}
