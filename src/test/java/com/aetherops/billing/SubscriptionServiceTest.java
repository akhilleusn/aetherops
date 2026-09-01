package com.aetherops.billing;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    private static final String STARTER_PRICE = "price_starter_123";
    private static final String TEAM_PRICE = "price_team_123";
    private static final String GROWTH_PRICE = "price_growth_123";

    @Mock private TenantRepository tenantRepository;

    private StripeConfig stripeConfig;
    private SubscriptionService subscriptionService;

    @BeforeEach
    void setUp() {
        stripeConfig = new StripeConfig();
        stripeConfig.setSecretKey("");
        stripeConfig.setPriceIdStarter(STARTER_PRICE);
        stripeConfig.setPriceIdTeam(TEAM_PRICE);
        stripeConfig.setPriceIdGrowth(GROWTH_PRICE);
        subscriptionService = new SubscriptionService(tenantRepository, stripeConfig);
    }

    @Test
    void createCheckoutSession_forFreePlan_rejectsWithoutTouchingRepository() {
        assertThatThrownBy(() -> subscriptionService.createCheckoutSession(UUID.randomUUID(), Plan.FREE))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(tenantRepository);
    }

    @Test
    void createCheckoutSession_forUnknownTenant_throwsResourceNotFound() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.createCheckoutSession(tenantId, Plan.STARTER))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createPortalSession_forTenantWithNoStripeCustomer_throws() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(tenantId).plan(Plan.FREE).build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> subscriptionService.createPortalSession(tenantId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Stripe customer");
    }

    @Test
    void createPortalSession_forUnknownTenant_throwsResourceNotFound() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.createPortalSession(tenantId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void priceIdFor_returnsConfiguredPriceForEachPaidPlan() {
        assertThat(subscriptionService.priceIdFor(Plan.STARTER)).isEqualTo(STARTER_PRICE);
        assertThat(subscriptionService.priceIdFor(Plan.TEAM)).isEqualTo(TEAM_PRICE);
        assertThat(subscriptionService.priceIdFor(Plan.GROWTH)).isEqualTo(GROWTH_PRICE);
    }

    @Test
    void priceIdFor_freePlan_throws() {
        assertThatThrownBy(() -> subscriptionService.priceIdFor(Plan.FREE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void priceIdFor_unconfiguredPrice_throws() {
        stripeConfig.setPriceIdStarter("");
        assertThatThrownBy(() -> subscriptionService.priceIdFor(Plan.STARTER))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void planForPriceId_mapsKnownPricesBackToPlans() {
        assertThat(subscriptionService.planForPriceId(STARTER_PRICE)).isEqualTo(Plan.STARTER);
        assertThat(subscriptionService.planForPriceId(TEAM_PRICE)).isEqualTo(Plan.TEAM);
        assertThat(subscriptionService.planForPriceId(GROWTH_PRICE)).isEqualTo(Plan.GROWTH);
    }

    @Test
    void planForPriceId_unknownPrice_defaultsToFree() {
        assertThat(subscriptionService.planForPriceId("price_totally_unknown")).isEqualTo(Plan.FREE);
    }

    @Test
    void planForPriceId_nullPrice_defaultsToFree() {
        assertThat(subscriptionService.planForPriceId(null)).isEqualTo(Plan.FREE);
    }
}
