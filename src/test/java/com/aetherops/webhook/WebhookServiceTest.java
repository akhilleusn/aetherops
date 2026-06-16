package com.aetherops.webhook;

import com.aetherops.config.FeatureFlag;
import com.aetherops.config.FeatureFlagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void deliver_withEndpoint_savesDelivery() throws Exception {
        UUID tenantId = UUID.randomUUID();
        WebhookEndpoint endpoint = WebhookEndpoint.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .url("https://example.com/hook")
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
        when(responseSpec.toBodilessEntity()).thenReturn(null);

        webhookService.deliver(tenantId, "incident.created", new Object());

        verify(deliveryRepository, atLeastOnce()).save(any());
    }

    @Test
    void register_savesEndpoint() {
        UUID tenantId = UUID.randomUUID();
        WebhookEndpoint endpoint = WebhookEndpoint.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .url("https://example.com/hook")
                .secret("test-secret")
                .active(true)
                .build();

        when(endpointRepository.save(any())).thenReturn(endpoint);

        WebhookEndpoint result = webhookService.register(tenantId, "https://example.com/hook", "secret");

        assertThat(result).isNotNull();
        verify(endpointRepository).save(any());
    }

    @Test
    void register_generatesSecret_whenNoneProvided() {
        UUID tenantId = UUID.randomUUID();
        when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WebhookEndpoint result = webhookService.register(tenantId, "https://example.com/hook", null);

        verify(endpointRepository).save(argThat(ep -> ep.getSecret() != null && !ep.getSecret().isBlank()));
    }
}
