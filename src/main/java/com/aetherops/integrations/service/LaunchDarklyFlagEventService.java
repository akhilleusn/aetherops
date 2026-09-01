package com.aetherops.integrations.service;

import com.aetherops.integrations.model.FlagEventResponse;
import com.aetherops.integrations.model.LaunchDarklyFlagEvent;
import com.aetherops.integrations.model.LaunchDarklyWebhookRequest;
import com.aetherops.integrations.repository.LaunchDarklyFlagEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LaunchDarklyFlagEventService {

    private final LaunchDarklyFlagEventRepository repository;

    @Transactional
    public void record(UUID tenantId, LaunchDarklyWebhookRequest request) {
        String description = request.getShortDescription() != null
            ? request.getShortDescription() : request.getTitleVerb();

        LaunchDarklyFlagEvent event = LaunchDarklyFlagEvent.builder()
            .tenantId(tenantId)
            .flagKey(request.getName())
            .changeDescription(description)
            .changedBy(request.getMember() != null ? request.getMember().getEmail() : null)
            .occurredAt(request.getDate() != null ? Instant.ofEpochMilli(request.getDate()) : Instant.now())
            .build();

        repository.save(event);
        log.info("Recorded LaunchDarkly flag event tenantId={} flagKey={}", tenantId, event.getFlagKey());
    }

    @Transactional(readOnly = true)
    public List<FlagEventResponse> findInRange(UUID tenantId, Instant from, Instant to) {
        return repository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtDesc(tenantId, from, to).stream()
            .map(e -> FlagEventResponse.builder()
                .id(e.getId())
                .flagKey(e.getFlagKey())
                .environment(e.getEnvironment())
                .changeDescription(e.getChangeDescription())
                .changedBy(e.getChangedBy())
                .occurredAt(e.getOccurredAt())
                .build())
            .toList();
    }
}
