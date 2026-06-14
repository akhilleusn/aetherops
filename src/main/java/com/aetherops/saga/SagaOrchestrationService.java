package com.aetherops.saga;

import com.aetherops.incident.service.IncidentSagaOrchestrator;
import com.aetherops.incident.service.IncidentService;
import com.aetherops.usage.UsageBillingService;
import com.aetherops.webhook.WebhookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
public class SagaOrchestrationService extends IncidentSagaOrchestrator {

    private final SagaStepRepository sagaStepRepository;

    public SagaOrchestrationService(IncidentService incidentService,
                                     UsageBillingService billingService,
                                     WebhookService webhookService,
                                     SagaStepRepository sagaStepRepository) {
        super(incidentService, billingService, webhookService);
        this.sagaStepRepository = sagaStepRepository;
    }

    @Override
    protected void onStep(String stepName, UUID sagaId, String status, String detail) {
        sagaStepRepository.save(SagaStep.builder()
                .sagaId(sagaId)
                .stepName(stepName)
                .status(status)
                .detail(detail)
                .recordedAt(Instant.now())
                .build());
        log.info("Saga step recorded sagaId={} step={} status={}", sagaId, stepName, status);
    }
}
