package com.aetherops.billing;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final TenantRepository tenantRepository;
    private final StripeConfig stripeConfig;

    public String createCheckoutSession(UUID tenantId, Plan targetPlan) {
        if (targetPlan == Plan.FREE) {
            throw new IllegalArgumentException("Cannot start a checkout session for the FREE plan");
        }
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        String priceId = priceIdFor(targetPlan);

        SessionCreateParams.Builder params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
            .setSuccessUrl(stripeConfig.getSuccessUrl())
            .setCancelUrl(stripeConfig.getCancelUrl())
            .addLineItem(SessionCreateParams.LineItem.builder()
                .setPrice(priceId)
                .setQuantity(1L)
                .build())
            .putMetadata("tenantId", tenantId.toString())
            .putMetadata("plan", targetPlan.name());

        if (tenant.getStripeCustomerId() != null) {
            params.setCustomer(tenant.getStripeCustomerId());
        }

        try {
            Session session = Session.create(params.build());
            log.info("Created Stripe checkout session tenantId={} plan={} sessionId={}",
                tenantId, targetPlan, session.getId());
            return session.getUrl();
        } catch (StripeException e) {
            throw new IllegalStateException("Failed to create Stripe checkout session", e);
        }
    }

    public String createPortalSession(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (tenant.getStripeCustomerId() == null) {
            throw new IllegalStateException("Tenant " + tenantId + " has no Stripe customer yet — subscribe first");
        }

        com.stripe.param.billingportal.SessionCreateParams params =
            com.stripe.param.billingportal.SessionCreateParams.builder()
                .setCustomer(tenant.getStripeCustomerId())
                .setReturnUrl(stripeConfig.getPortalReturnUrl())
                .build();

        try {
            com.stripe.model.billingportal.Session portalSession =
                com.stripe.model.billingportal.Session.create(params);
            return portalSession.getUrl();
        } catch (StripeException e) {
            throw new IllegalStateException("Failed to create Stripe billing portal session", e);
        }
    }

    String priceIdFor(Plan plan) {
        String priceId = switch (plan) {
            case STARTER -> stripeConfig.getPriceIdStarter();
            case TEAM    -> stripeConfig.getPriceIdTeam();
            case GROWTH  -> stripeConfig.getPriceIdGrowth();
            case FREE    -> throw new IllegalArgumentException("FREE plan has no Stripe price");
        };
        if (priceId == null || priceId.isBlank()) {
            throw new IllegalStateException("No Stripe price id configured for plan " + plan);
        }
        return priceId;
    }

    /** Maps a Stripe subscription's price id back to our Plan. Falls back to FREE for unrecognized prices. */
    Plan planForPriceId(String priceId) {
        if (priceId != null && priceId.equals(stripeConfig.getPriceIdStarter())) return Plan.STARTER;
        if (priceId != null && priceId.equals(stripeConfig.getPriceIdTeam())) return Plan.TEAM;
        if (priceId != null && priceId.equals(stripeConfig.getPriceIdGrowth())) return Plan.GROWTH;
        log.warn("Unrecognized Stripe price id={}, defaulting to FREE", priceId);
        return Plan.FREE;
    }
}
