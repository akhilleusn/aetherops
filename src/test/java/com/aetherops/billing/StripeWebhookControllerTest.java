package com.aetherops.billing;

import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StripeWebhookControllerTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret";

    @Mock private TenantRepository tenantRepository;
    @Mock private StripeWebhookEventRepository webhookEventRepository;
    @Mock private SubscriptionService subscriptionService;

    private StripeConfig stripeConfig;
    private StripeWebhookController controller;

    @BeforeEach
    void setUp() {
        stripeConfig = new StripeConfig();
        stripeConfig.setSecretKey("");
        stripeConfig.setWebhookSecret(WEBHOOK_SECRET);
        controller = new StripeWebhookController(stripeConfig, tenantRepository, webhookEventRepository, subscriptionService);

        lenient().when(webhookEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // Independently computed per Stripe's documented signing scheme — does not
    // reuse the Stripe SDK's own signing code, only its verification code.
    private static String sign(String payload, long timestamp) {
        try {
            String signedPayload = timestamp + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String hex = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
            return "t=" + timestamp + ",v1=" + hex;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sign(String payload) {
        return sign(payload, Instant.now().getEpochSecond());
    }

    private static String checkoutCompletedPayload(String eventId, UUID tenantId, String customerId, String subscriptionId) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "checkout.session.completed",
              "data": {
                "object": {
                  "id": "cs_test_123",
                  "object": "checkout.session",
                  "customer": "%s",
                  "subscription": "%s",
                  "metadata": { "tenantId": "%s", "plan": "STARTER" }
                }
              }
            }""".formatted(eventId, customerId, subscriptionId, tenantId);
    }

    private static String subscriptionUpdatedPayload(String eventId, String eventType, String customerId,
                                                       String subscriptionId, String status, String priceId, long periodEnd) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "%s",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "subscription",
                  "customer": "%s",
                  "status": "%s",
                  "current_period_end": %d,
                  "items": {
                    "object": "list",
                    "data": [
                      { "id": "si_123", "object": "subscription_item", "price": { "id": "%s", "object": "price" } }
                    ]
                  }
                }
              }
            }""".formatted(eventId, eventType, subscriptionId, customerId, status, periodEnd, priceId);
    }

    private static String unhandledEventPayload(String eventId) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "customer.created",
              "data": { "object": { "id": "cus_123", "object": "customer" } }
            }""".formatted(eventId);
    }

    @Test
    void handle_invalidSignature_returnsBadRequest_andRecordsNoEvent() {
        String payload = unhandledEventPayload("evt_1");

        ResponseEntity<Void> response = controller.handle(payload, "t=1,v1=deadbeef");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(webhookEventRepository);
    }

    @Test
    void handle_missingSignatureHeader_returnsBadRequest() {
        ResponseEntity<Void> response = controller.handle(unhandledEventPayload("evt_1"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void handle_duplicateEventId_skipsProcessing() {
        String payload = unhandledEventPayload("evt_dup");
        when(webhookEventRepository.existsById("evt_dup")).thenReturn(true);

        ResponseEntity<Void> response = controller.handle(payload, sign(payload));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(webhookEventRepository, never()).save(any());
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void handle_unhandledEventType_stillRecordsEventAndReturnsOk() {
        String payload = unhandledEventPayload("evt_new");
        when(webhookEventRepository.existsById("evt_new")).thenReturn(false);

        ResponseEntity<Void> response = controller.handle(payload, sign(payload));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(webhookEventRepository).save(argThat(e -> e.getEventId().equals("evt_new")));
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void handle_checkoutSessionCompleted_updatesTenantFromMetadata() {
        UUID tenantId = UUID.randomUUID();
        String payload = checkoutCompletedPayload("evt_checkout", tenantId, "cus_123", "sub_123");
        when(webhookEventRepository.existsById("evt_checkout")).thenReturn(false);

        Tenant tenant = Tenant.builder().id(tenantId).plan(Plan.FREE).build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        ResponseEntity<Void> response = controller.handle(payload, sign(payload));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(captor.capture());
        assertThat(captor.getValue().getStripeCustomerId()).isEqualTo("cus_123");
        assertThat(captor.getValue().getStripeSubscriptionId()).isEqualTo("sub_123");
        assertThat(captor.getValue().getPlan()).isEqualTo(Plan.STARTER);
    }

    @Test
    void handle_checkoutSessionCompleted_unknownTenant_doesNotThrow() {
        UUID tenantId = UUID.randomUUID();
        String payload = checkoutCompletedPayload("evt_checkout2", tenantId, "cus_123", "sub_123");
        when(webhookEventRepository.existsById("evt_checkout2")).thenReturn(false);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.handle(payload, sign(payload));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void handle_subscriptionUpdated_syncsPlanStatusAndPeriodEnd() {
        String payload = subscriptionUpdatedPayload(
            "evt_sub_updated", "customer.subscription.updated",
            "cus_456", "sub_456", "active", "price_team_123", 1700003600L);
        when(webhookEventRepository.existsById("evt_sub_updated")).thenReturn(false);

        Tenant tenant = Tenant.builder().id(UUID.randomUUID()).plan(Plan.FREE).stripeCustomerId("cus_456").build();
        when(tenantRepository.findByStripeCustomerId("cus_456")).thenReturn(Optional.of(tenant));
        when(subscriptionService.planForPriceId("price_team_123")).thenReturn(Plan.TEAM);

        ResponseEntity<Void> response = controller.handle(payload, sign(payload));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(captor.capture());
        assertThat(captor.getValue().getPlan()).isEqualTo(Plan.TEAM);
        assertThat(captor.getValue().getSubscriptionStatus()).isEqualTo("active");
        assertThat(captor.getValue().getStripeSubscriptionId()).isEqualTo("sub_456");
        assertThat(captor.getValue().getCurrentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1700003600L));
    }

    @Test
    void handle_subscriptionDeleted_revertsTenantToFree() {
        String payload = subscriptionUpdatedPayload(
            "evt_sub_deleted", "customer.subscription.deleted",
            "cus_789", "sub_789", "canceled", "price_growth_123", 1700003600L);
        when(webhookEventRepository.existsById("evt_sub_deleted")).thenReturn(false);

        Tenant tenant = Tenant.builder()
            .id(UUID.randomUUID()).plan(Plan.GROWTH).stripeCustomerId("cus_789")
            .stripeSubscriptionId("sub_789").subscriptionStatus("active")
            .currentPeriodEnd(Instant.now()).build();
        when(tenantRepository.findByStripeCustomerId("cus_789")).thenReturn(Optional.of(tenant));

        ResponseEntity<Void> response = controller.handle(payload, sign(payload));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(captor.capture());
        assertThat(captor.getValue().getPlan()).isEqualTo(Plan.FREE);
        assertThat(captor.getValue().getSubscriptionStatus()).isEqualTo("canceled");
        assertThat(captor.getValue().getStripeSubscriptionId()).isNull();
        assertThat(captor.getValue().getCurrentPeriodEnd()).isNull();
        verifyNoInteractions(subscriptionService);
    }
}
