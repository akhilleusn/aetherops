package com.aetherops.integrations.webhook;

/**
 * Inbound alert-source webhook providers this codebase can verify a request signature for.
 * Deliberately does NOT include every provider {@link com.aetherops.integrations.controller.
 * IntegrationsWebhookController} accepts — only the ones with a confirmed, documented signing
 * mechanism (Phase 1 Step E, finding 5 recon). AWS SNS (cloudwatch) is verified separately by
 * {@link SnsSignatureVerifier} since its signing is certificate-based, not a shared secret.
 */
public enum WebhookSigningProvider {
    GITHUB, PAGERDUTY, GRAFANA
}
