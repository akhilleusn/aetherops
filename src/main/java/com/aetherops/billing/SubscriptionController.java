package com.aetherops.billing;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.model.Plan;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/billing")
@RequiredArgsConstructor
@Tag(name = "Subscriptions", description = "Stripe checkout and billing portal session creation")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @Data
    public static class CreateCheckoutSessionRequest {
        @NotNull
        private Plan plan;
    }

    @PostMapping("/checkout-session")
    @Operation(summary = "Create a Stripe Checkout session to subscribe/upgrade a tenant to a paid plan")
    public ApiResponse<Map<String, String>> createCheckoutSession(
            @PathVariable UUID tenantId,
            @RequestBody CreateCheckoutSessionRequest request) {
        String url = subscriptionService.createCheckoutSession(tenantId, request.getPlan());
        return ApiResponse.success(Map.of("url", url));
    }

    @PostMapping("/portal-session")
    @Operation(summary = "Create a Stripe Billing Portal session (upgrade/downgrade/cancel/invoice history)")
    public ApiResponse<Map<String, String>> createPortalSession(@PathVariable UUID tenantId) {
        String url = subscriptionService.createPortalSession(tenantId);
        return ApiResponse.success(Map.of("url", url));
    }
}
