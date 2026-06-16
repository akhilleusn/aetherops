package com.aetherops.incident.service;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentCorrelationService {

    private static final int CORRELATION_WINDOW_MINUTES = 5;

    private final IncidentRepository incidentRepository;

    @Transactional
    public Optional<Incident> correlate(UUID tenantId, String serviceId, String fingerprint, Instant now) {
        Instant since = now.minus(CORRELATION_WINDOW_MINUTES, ChronoUnit.MINUTES);
        List<Incident> candidates = incidentRepository
                .findRecentOpenByTenantAndService(tenantId, serviceId, IncidentStatus.OPEN, since);

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Incident target = candidates.get(0);
        target.setEventCount(target.getEventCount() + 1);
        target.setLastSeenAt(now);
        if (target.getCorrelationGroupId() == null) {
            target.setCorrelationGroupId(target.getId());
        }

        Incident saved = incidentRepository.save(target);
        log.info("Correlated event to incident id={} eventCount={} tenantId={} service={}",
                saved.getId(), saved.getEventCount(), tenantId, serviceId);
        return Optional.of(saved);
    }
}
