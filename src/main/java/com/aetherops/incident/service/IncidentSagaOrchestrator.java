package com.aetherops.incident.service;

import com.aetherops.incident.model.CreateIncidentRequest;
import com.aetherops.incident.model.IncidentResponse;
import com.aetherops.usage.UsageBillingService;
import com.aetherops.webhook.WebhookService;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class IncidentSagaOrchestrator {

    protected final IncidentService incidentService;
    protected final UsageBillingService billingService;
    protected final WebhookService webhookService;

    public IncidentSagaOrchestrator(IncidentService incidentService,
                                     UsageBillingService billingService,
                                     WebhookService webhookService) {
        this.incidentService = incidentService;
        this.billingService = billingService;
        this.webhookService = webhookService;
    }

    public IncidentResponse orchestrate(CreateIncidentRequest request, String idempotencyKey) {
        UUID incidentId = null;
        try {
            IncidentResponse incident = incidentService.create(request, idempotencyKey);
            incidentId = incident.getId();
            onStep("CREATE_INCIDENT", incidentId, "SUCCESS", null);

            billingService.record(request.getTenantId(), "INCIDENT_CREATED", 1);
            onStep("RECORD_BILLING", incidentId, "SUCCESS", null);

            webhookService.deliver(request.getTenantId(), "saga.incident.created", incident);
            onStep("DELIVER_WEBHOOK", incidentId, "SUCCESS", null);

            log.info("Saga completed incidentId={} tenant={}", incidentId, request.getTenantId());
            return incident;

        } catch (Exception e) {
            log.error("Saga failed at step incidentId={} reason={}", incidentId, e.getMessage());
            if (incidentId != null) {
                onStep("COMPENSATE", incidentId, "FAILED", e.getMessage());
                compensate(incidentId);
            }
            throw e;
        }
    }

    public void compensate(UUID incidentId) {
        incidentService.softDelete(incidentId);
        log.warn("Saga compensated: soft-deleted incidentId={}", incidentId);
    }

    protected void onStep(String stepName, UUID sagaId, String status, String detail) {
        // hook for subclasses to record step state
    }
}
