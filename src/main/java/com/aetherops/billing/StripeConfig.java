package com.aetherops.billing;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "stripe")
@Slf4j
public class StripeConfig {
    private String secretKey;
    private String webhookSecret;
    private String priceIdStarter;
    private String priceIdTeam;
    private String priceIdGrowth;
    private String successUrl;
    private String cancelUrl;
    private String portalReturnUrl;

    @PostConstruct
    public void init() {
        if (secretKey == null || secretKey.isBlank()) {
            log.warn("Stripe secret key not configured — billing integration is disabled");
            return;
        }
        Stripe.apiKey = secretKey;
        log.info("Stripe SDK initialized");
    }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public String getPriceIdStarter() { return priceIdStarter; }
    public void setPriceIdStarter(String priceIdStarter) { this.priceIdStarter = priceIdStarter; }
    public String getPriceIdTeam() { return priceIdTeam; }
    public void setPriceIdTeam(String priceIdTeam) { this.priceIdTeam = priceIdTeam; }
    public String getPriceIdGrowth() { return priceIdGrowth; }
    public void setPriceIdGrowth(String priceIdGrowth) { this.priceIdGrowth = priceIdGrowth; }
    public String getSuccessUrl() { return successUrl; }
    public void setSuccessUrl(String successUrl) { this.successUrl = successUrl; }
    public String getCancelUrl() { return cancelUrl; }
    public void setCancelUrl(String cancelUrl) { this.cancelUrl = cancelUrl; }
    public String getPortalReturnUrl() { return portalReturnUrl; }
    public void setPortalReturnUrl(String portalReturnUrl) { this.portalReturnUrl = portalReturnUrl; }
}
