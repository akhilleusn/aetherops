package com.aetherops.integrations.webhook;

/**
 * Inbound alert-source webhook providers this codebase can verify. Two verification families:
 *
 * <p>HMAC (a real cryptographic signature, verified by {@link WebhookSignatureFilter}'s
 * per-provider HMAC methods): GITHUB, PAGERDUTY, GRAFANA.
 *
 * <p>STATIC_TOKEN (no signing mechanism exists for these providers at all — this is a plain
 * shared secret carried in a header the tenant configures on the sending side, or for ROLLBAR
 * a URL query parameter, per that provider's own documented convention; see
 * {@link WebhookSignatureFilter#verifyStaticToken}): DATADOG, NEW_RELIC, OPSGENIE, ROLLBAR,
 * ALERTMANAGER. This does not make forgery cryptographically impossible the way HMAC does —
 * it closes the specific hole of one provider's leaked/shared X-Api-Key also authenticating
 * as every OTHER provider on the same project, by requiring a second, provider-scoped secret.
 *
 * <p>Deliberately excluded: SPLUNK (its built-in webhook alert action supports no custom
 * headers or auth mechanism of any kind to carry a secret through — see the comment on
 * {@code IntegrationsWebhookController.splunk(...)}; a documented, accepted risk, not an
 * oversight). SENTRY, VICTOROPS, SUMOLOGIC (unconfirmed schemes from E2 recon — implementing
 * a guessed scheme risks silently dropping real alerts, worse than the current gap).
 *
 * <p>AWS SNS (cloudwatch) is verified separately by {@link SnsSignatureVerifier} since its
 * signing is certificate-based, not a shared secret at all.
 */
public enum WebhookSigningProvider {
    GITHUB, PAGERDUTY, GRAFANA,
    DATADOG, NEW_RELIC, OPSGENIE, ROLLBAR, ALERTMANAGER
}
