package com.aetherops.billing;

import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * All handlers here are DB-only (no outbound Stripe API calls) so the webhook
 * responds fast and has no external failure point of its own. Plan/status/
 * period-end are treated as authoritative from customer.subscription.* events,
 * not from checkout.session.completed — Stripe fires a subscription event
 * immediately after checkout anyway, so there is no need to call back to
 * Stripe just to read data already present in the subscription payload.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookController {

    private static final List<String> SUBSCRIPTION_STATE_EVENTS =
        List.of("customer.subscription.created", "customer.subscription.updated");

    private final StripeConfig stripeConfig;
    private final TenantRepository tenantRepository;
    private final StripeWebhookEventRepository webhookEventRepository;
    private final SubscriptionService subscriptionService;

    @PostMapping("/webhooks/stripe")
    public ResponseEntity<Void> handle(@RequestBody String payload,
                                        @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (signature == null) {
            log.warn("Rejected Stripe webhook: missing Stripe-Signature header");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, stripeConfig.getWebhookSecret());
        } catch (SignatureVerificationException | IllegalArgumentException e) {
            log.warn("Rejected Stripe webhook: invalid signature");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (webhookEventRepository.existsById(event.getId())) {
            log.debug("Ignoring duplicate Stripe event id={}", event.getId());
            return ResponseEntity.ok().build();
        }
        webhookEventRepository.save(StripeWebhookEvent.builder()
            .eventId(event.getId())
            .eventType(event.getType())
            .build());

        StripeObject dataObject;
        try {
            dataObject = event.getDataObjectDeserializer().getObject()
                .orElseGet(() -> {
                    try {
                        return event.getDataObjectDeserializer().deserializeUnsafe();
                    } catch (com.stripe.exception.EventDataObjectDeserializationException e) {
                        throw new IllegalStateException(e);
                    }
                });
        } catch (IllegalStateException e) {
            log.warn("Failed to deserialize Stripe event data object type={} id={}", event.getType(), event.getId(), e);
            return ResponseEntity.ok().build();
        }

        if (event.getType().equals("checkout.session.completed")) {
            handleCheckoutCompleted((Session) dataObject);
        } else if (SUBSCRIPTION_STATE_EVENTS.contains(event.getType())) {
            handleSubscriptionState((Subscription) dataObject);
        } else if (event.getType().equals("customer.subscription.deleted")) {
            handleSubscriptionDeleted((Subscription) dataObject);
        } else if (event.getType().equals("invoice.payment_succeeded")
                || event.getType().equals("payment_intent.succeeded")) {
            log.info("Stripe event received type={} id={} (informational — subscription state is driven by " +
                "customer.subscription.* events)", event.getType(), event.getId());
        } else {
            log.debug("Unhandled Stripe event type={}", event.getType());
        }

        return ResponseEntity.ok().build();
    }

    private void handleCheckoutCompleted(Session session) {
        String tenantIdRaw = session.getMetadata() != null ? session.getMetadata().get("tenantId") : null;
        String planRaw = session.getMetadata() != null ? session.getMetadata().get("plan") : null;
        if (tenantIdRaw == null) {
            log.warn("checkout.session.completed with no tenantId metadata, sessionId={}", session.getId());
            return;
        }

        Optional<Tenant> tenantOpt = tenantRepository.findById(java.util.UUID.fromString(tenantIdRaw));
        if (tenantOpt.isEmpty()) {
            log.warn("checkout.session.completed for unknown tenantId={}", tenantIdRaw);
            return;
        }

        Tenant tenant = tenantOpt.get();
        tenant.setStripeCustomerId(session.getCustomer());
        tenant.setStripeSubscriptionId(session.getSubscription());
        if (planRaw != null) {
            tenant.setPlan(Plan.valueOf(planRaw));
        }
        tenantRepository.save(tenant);

        log.info("Checkout completed tenantId={} customerId={} subscriptionId={}",
            tenantIdRaw, session.getCustomer(), session.getSubscription());
    }

    private void handleSubscriptionState(Subscription subscription) {
        Optional<Tenant> tenantOpt = tenantRepository.findByStripeCustomerId(subscription.getCustomer());
        if (tenantOpt.isEmpty()) {
            log.warn("Subscription event for unknown Stripe customerId={}", subscription.getCustomer());
            return;
        }

        Tenant tenant = tenantOpt.get();
        tenant.setStripeSubscriptionId(subscription.getId());
        tenant.setSubscriptionStatus(subscription.getStatus());
        if (subscription.getCurrentPeriodEnd() != null) {
            tenant.setCurrentPeriodEnd(Instant.ofEpochSecond(subscription.getCurrentPeriodEnd()));
        }

        String priceId = subscription.getItems() != null && !subscription.getItems().getData().isEmpty()
            ? subscription.getItems().getData().get(0).getPrice().getId()
            : null;
        tenant.setPlan(subscriptionService.planForPriceId(priceId));

        tenantRepository.save(tenant);

        log.info("Synced subscription state tenantId={} plan={} status={}",
            tenant.getId(), tenant.getPlan(), subscription.getStatus());
    }

    private void handleSubscriptionDeleted(Subscription subscription) {
        Optional<Tenant> tenantOpt = tenantRepository.findByStripeCustomerId(subscription.getCustomer());
        if (tenantOpt.isEmpty()) {
            log.warn("subscription.deleted for unknown Stripe customerId={}", subscription.getCustomer());
            return;
        }

        Tenant tenant = tenantOpt.get();
        tenant.setPlan(Plan.FREE);
        tenant.setSubscriptionStatus("canceled");
        tenant.setStripeSubscriptionId(null);
        tenant.setCurrentPeriodEnd(null);
        tenantRepository.save(tenant);

        log.info("Subscription canceled, reverted to FREE tenantId={}", tenant.getId());
    }
}
