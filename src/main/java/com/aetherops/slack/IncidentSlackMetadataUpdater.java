package com.aetherops.slack;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists Slack channel/thread metadata for an incident as its own @Transactional
 * boundary. IncidentSlackNotificationService.notifyViaBotToken is private (self-invocation
 * isn't proxied), so calling incidentRepository.save() directly from it never goes through
 * a method TenantContextInterceptor's pointcut (@annotation(Transactional)) can match --
 * app.tenant_id never gets propagated, and the merge() on this already-ID'd incident is
 * silently blocked by RLS. This class exists solely to give that write a real join point.
 */
@Service
@RequiredArgsConstructor
public class IncidentSlackMetadataUpdater {

    private final IncidentRepository incidentRepository;

    @Transactional
    public void update(Incident incident, String channel, String threadTs) {
        incident.setSlackChannelId(channel);
        incident.setSlackThreadTs(threadTs);
        incidentRepository.save(incident);
    }
}
