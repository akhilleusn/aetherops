package com.aetherops.incident.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
public class IncidentDomainEventListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncidentCreated(IncidentCreatedDomainEvent event) {
        log.info("Domain event INCIDENT_CREATED incidentId={} tenant={} severity={} title={}",
                event.incidentId(), event.tenantId(), event.severity(), event.title());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncidentResolved(IncidentResolvedDomainEvent event) {
        log.info("Domain event INCIDENT_RESOLVED incidentId={} tenant={} resolvedAt={}",
                event.incidentId(), event.tenantId(), event.resolvedAt());
    }
}
