package com.aetherops.integrations;

import com.aetherops.apikey.model.ApiKey;
import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.integrations.controller.LaunchDarklyController;
import com.aetherops.integrations.model.FlagEventResponse;
import com.aetherops.integrations.model.LaunchDarklyWebhookRequest;
import com.aetherops.integrations.service.LaunchDarklyFlagEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LaunchDarklyControllerTest {

    @Mock private ApiKeyService apiKeyService;
    @Mock private LaunchDarklyFlagEventService flagEventService;

    @InjectMocks
    private LaunchDarklyController controller;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void webhook_resolvesApiKeyAndRecordsEvent() {
        ApiKey apiKey = ApiKey.builder().tenantId(tenantId).build();
        when(apiKeyService.resolve("key-123")).thenReturn(apiKey);
        LaunchDarklyWebhookRequest request = new LaunchDarklyWebhookRequest();
        request.setName("flag-a");

        controller.webhook(request, "key-123");

        verify(flagEventService).record(tenantId, request);
    }

    @Test
    void list_noDatesProvided_defaultsToTrailingTwentyFourHours() {
        when(flagEventService.findInRange(eq(tenantId), any(), any())).thenReturn(List.of());

        controller.list(tenantId, null, null);

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(flagEventService).findInRange(eq(tenantId), fromCaptor.capture(), toCaptor.capture());

        assertThat(Duration.between(fromCaptor.getValue(), toCaptor.getValue()).toHours()).isEqualTo(24);
    }

    @Test
    void list_explicitDates_passedThroughUnchanged() {
        Instant from = Instant.now().minusSeconds(7200);
        Instant to = Instant.now();
        when(flagEventService.findInRange(tenantId, from, to)).thenReturn(List.of());

        controller.list(tenantId, from, to);

        verify(flagEventService).findInRange(tenantId, from, to);
    }

    @Test
    void list_returnsServiceResultWrappedInApiResponse() {
        FlagEventResponse event = FlagEventResponse.builder().flagKey("flag-a").build();
        when(flagEventService.findInRange(eq(tenantId), any(), any())).thenReturn(List.of(event));

        ApiResponse<List<FlagEventResponse>> response = controller.list(tenantId, null, null);

        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getFlagKey()).isEqualTo("flag-a");
    }
}
