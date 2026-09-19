package com.aetherops.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wraps every {@link WebhookDelivery} write in its own {@code @Transactional} bean.
 * {@code WebhookService.deliver} is {@code @Async}, and both writes here used to be bare
 * {@code deliveryRepository.save(...)} calls reached via self-invocation from
 * {@code deliverInternal}/{@code attemptDelivery} -- calling this bean instead means each write
 * goes through a proxy-visible {@code @Transactional} boundary, the same fix already applied to
 * {@code IncidentSlackMetadataUpdater} and {@code FailedAnalysisRecorder}. {@code webhook_deliveries}
 * has no RLS policy today, so this isn't fixing a live bug -- it stops this exact bug from
 * reappearing silently the moment RLS is added to that table.
 */
@Service
@RequiredArgsConstructor
public class WebhookDeliveryRecorder {

    private final WebhookDeliveryRepository deliveryRepository;

    @Transactional
    public WebhookDelivery create(WebhookDelivery delivery) {
        return deliveryRepository.save(delivery);
    }

    @Transactional
    public void save(WebhookDelivery delivery) {
        deliveryRepository.save(delivery);
    }
}
