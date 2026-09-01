package com.aetherops.integrations;

import com.aetherops.integrations.model.FlagEventResponse;
import com.aetherops.integrations.model.LaunchDarklyFlagEvent;
import com.aetherops.integrations.model.LaunchDarklyWebhookRequest;
import com.aetherops.integrations.repository.LaunchDarklyFlagEventRepository;
import com.aetherops.integrations.service.LaunchDarklyFlagEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LaunchDarklyFlagEventServiceTest {

    @Mock private LaunchDarklyFlagEventRepository repository;

    @InjectMocks
    private LaunchDarklyFlagEventService service;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void record_mapsEpochMillisToInstant() {
        LaunchDarklyWebhookRequest.Member member = new LaunchDarklyWebhookRequest.Member();
        member.setEmail("alice@acme.com");
        LaunchDarklyWebhookRequest request = new LaunchDarklyWebhookRequest();
        request.setName("new-checkout-flow");
        request.setShortDescription("turned on for 50% of users");
        request.setMember(member);
        request.setDate(1_700_000_000_000L);

        service.record(tenantId, request);

        ArgumentCaptor<LaunchDarklyFlagEvent> captor = ArgumentCaptor.forClass(LaunchDarklyFlagEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getFlagKey()).isEqualTo("new-checkout-flow");
        assertThat(captor.getValue().getChangeDescription()).isEqualTo("turned on for 50% of users");
        assertThat(captor.getValue().getChangedBy()).isEqualTo("alice@acme.com");
        assertThat(captor.getValue().getOccurredAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
    }

    @Test
    void record_missingShortDescription_fallsBackToTitleVerb() {
        LaunchDarklyWebhookRequest request = new LaunchDarklyWebhookRequest();
        request.setName("some-flag");
        request.setTitleVerb("turned on");

        service.record(tenantId, request);

        ArgumentCaptor<LaunchDarklyFlagEvent> captor = ArgumentCaptor.forClass(LaunchDarklyFlagEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getChangeDescription()).isEqualTo("turned on");
    }

    @Test
    void record_missingDate_defaultsToNow() {
        LaunchDarklyWebhookRequest request = new LaunchDarklyWebhookRequest();
        request.setName("some-flag");

        Instant before = Instant.now();
        service.record(tenantId, request);
        Instant after = Instant.now();

        ArgumentCaptor<LaunchDarklyFlagEvent> captor = ArgumentCaptor.forClass(LaunchDarklyFlagEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOccurredAt()).isBetween(before, after);
    }

    @Test
    void record_missingMember_changedByIsNull() {
        LaunchDarklyWebhookRequest request = new LaunchDarklyWebhookRequest();
        request.setName("some-flag");

        service.record(tenantId, request);

        ArgumentCaptor<LaunchDarklyFlagEvent> captor = ArgumentCaptor.forClass(LaunchDarklyFlagEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getChangedBy()).isNull();
    }

    @Test
    void findInRange_mapsEntitiesToResponses() {
        LaunchDarklyFlagEvent event = LaunchDarklyFlagEvent.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).flagKey("flag-a")
            .changeDescription("desc").changedBy("bob@acme.com").occurredAt(Instant.now())
            .build();
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now();
        when(repository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtDesc(eq(tenantId), any(), any()))
            .thenReturn(List.of(event));

        List<FlagEventResponse> result = service.findInRange(tenantId, from, to);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFlagKey()).isEqualTo("flag-a");
        assertThat(result.get(0).getChangedBy()).isEqualTo("bob@acme.com");
    }
}
