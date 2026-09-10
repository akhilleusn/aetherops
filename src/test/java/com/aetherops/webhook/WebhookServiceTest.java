package com.aetherops.webhook;

import com.aetherops.common.exception.SsrfBlockedException;
import com.aetherops.config.FeatureFlag;
import com.aetherops.config.FeatureFlagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock private WebhookEndpointRepository endpointRepository;
    @Mock private WebhookDeliveryRepository deliveryRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private RestClient.Builder restClientBuilder;
    @Mock private FeatureFlagService featureFlagService;
    @Mock private WebhookSignatureVerifier signatureVerifier;

    @InjectMocks
    private WebhookService webhookService;

    @BeforeEach
    void setUp() {
        // SsrfGuard.disableRedirects(restClientBuilder) calls .requestFactory(...), which a
        // real RestClient.Builder returns `this` from (fluent/mutating) -- mirror that so a
        // subsequent .build() still hits whatever a test stubs it to return.
        lenient().when(restClientBuilder.requestFactory(any())).thenReturn(restClientBuilder);
    }

    @Test
    void deliver_noEndpoints_doesNothing() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(featureFlagService.isEnabled(FeatureFlag.WEBHOOKS, tenantId)).thenReturn(true);
        when(endpointRepository.findByTenantIdAndActiveTrue(tenantId)).thenReturn(List.of());

        webhookService.deliver(tenantId, "incident.created", new Object());

        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void deliver_skips_whenFeatureFlagDisabled() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(featureFlagService.isEnabled(FeatureFlag.WEBHOOKS, tenantId)).thenReturn(false);

        webhookService.deliver(tenantId, "incident.created", new Object());

        verify(endpointRepository, never()).findByTenantIdAndActiveTrue(any());
        verify(deliveryRepository, never()).save(any());
    }

    // 8.8.8.8 (Google public DNS) is used as a stable, always-public IP literal rather than a
    // hostname like "example.com" specifically so these tests never depend on real DNS
    // resolution -- SsrfGuard.validate() now genuinely resolves whatever host is given it.
    @Test
    void deliver_withEndpoint_savesDelivery() throws Exception {
        UUID tenantId = UUID.randomUUID();
        WebhookEndpoint endpoint = WebhookEndpoint.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .url("https://8.8.8.8/hook")
                .secret("test-secret")
                .active(true)
                .build();

        when(featureFlagService.isEnabled(FeatureFlag.WEBHOOKS, tenantId)).thenReturn(true);
        when(endpointRepository.findByTenantIdAndActiveTrue(tenantId)).thenReturn(List.of(endpoint));
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"test\":\"data\"}");
        when(deliveryRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(signatureVerifier.computeHmacSha256(anyString(), anyString())).thenReturn("sha256=abc123");

        RestClient mockClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClientBuilder.build()).thenReturn(mockClient);
        when(mockClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        webhookService.deliver(tenantId, "incident.created", new Object());

        verify(deliveryRepository, atLeastOnce()).save(any());
    }

    @Test
    void register_savesEndpoint() {
        UUID tenantId = UUID.randomUUID();
        WebhookEndpoint endpoint = WebhookEndpoint.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .url("https://8.8.8.8/hook")
                .secret("test-secret")
                .active(true)
                .build();

        when(endpointRepository.save(any())).thenReturn(endpoint);

        WebhookEndpoint result = webhookService.register(tenantId, "https://8.8.8.8/hook", "secret");

        assertThat(result).isNotNull();
        verify(endpointRepository).save(any());
    }

    @Test
    void register_generatesSecret_whenNoneProvided() {
        UUID tenantId = UUID.randomUUID();
        when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WebhookEndpoint result = webhookService.register(tenantId, "https://8.8.8.8/hook", null);

        verify(endpointRepository).save(argThat(ep -> ep.getSecret() != null && !ep.getSecret().isBlank()));
    }

    // --- SSRF guard (Phase 2 remediation, finding 6 — see STEP_REPORT.md) ---

    @Test
    void register_privateRangeUrl_rejectedWithoutSaving() {
        assertThatThrownBy(() -> webhookService.register(UUID.randomUUID(), "https://10.0.0.5", "secret"))
            .isInstanceOf(SsrfBlockedException.class);
        verify(endpointRepository, never()).save(any());
    }

    @Test
    void register_linkLocalMetadataUrl_rejected() {
        assertThatThrownBy(() -> webhookService.register(UUID.randomUUID(), "https://169.254.169.254", "secret"))
            .isInstanceOf(SsrfBlockedException.class);
        verify(endpointRepository, never()).save(any());
    }

    @Test
    void register_httpUrl_rejected() {
        assertThatThrownBy(() -> webhookService.register(UUID.randomUUID(), "http://8.8.8.8", "secret"))
            .isInstanceOf(SsrfBlockedException.class);
        verify(endpointRepository, never()).save(any());
    }

    /** Registration only narrows the window — DNS for an already-registered url can be
     *  repointed later, which is why delivery re-validates too. Bypasses register()'s own
     *  guard by saving the endpoint directly through the (mocked) repository. */
    private WebhookEndpoint deliverToUrl(String url) throws Exception {
        UUID tenantId = UUID.randomUUID();
        WebhookEndpoint endpoint = WebhookEndpoint.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).url(url).secret("test-secret").active(true).build();

        when(featureFlagService.isEnabled(FeatureFlag.WEBHOOKS, tenantId)).thenReturn(true);
        when(endpointRepository.findByTenantIdAndActiveTrue(tenantId)).thenReturn(List.of(endpoint));
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"test\":\"data\"}");
        when(deliveryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        webhookService.deliver(tenantId, "incident.created", new Object());
        return endpoint;
    }

    @Test
    void attemptDelivery_privateRangeUrl_rejectedWithoutCallingRestClient() throws Exception {
        deliverToUrl("https://10.0.0.5/hook");

        verifyNoInteractions(restClientBuilder);
        // deliver() saves once to create the PENDING row, attemptDelivery() saves again with
        // the final status -- same mutable WebhookDelivery instance both times, so the last
        // capture reflects its final state.
        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository, times(2)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("RETRYING");
    }

    @Test
    void attemptDelivery_redirectResponse_rejectedNotFollowed() throws Exception {
        RestClient mockClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClientBuilder.build()).thenReturn(mockClient);
        when(mockClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity())
            .thenReturn(ResponseEntity.status(HttpStatus.FOUND).header("Location", "http://169.254.169.254/").build());
        when(signatureVerifier.computeHmacSha256(anyString(), anyString())).thenReturn("sha256=abc123");

        deliverToUrl("https://8.8.8.8/hook");

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository, times(2)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("RETRYING");
    }
}
