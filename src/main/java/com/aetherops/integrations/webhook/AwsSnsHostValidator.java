package com.aetherops.integrations.webhook;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Real AWS SNS URLs (both subscription-confirmation SubscribeURLs and signing-certificate
 * SigningCertURLs) are always https://sns.{region}.amazonaws.com/... (or the
 * .amazonaws.com.cn China partition). Shared by IntegrationsWebhookController's existing
 * SubscriptionConfirmation flow and by SnsSignatureVerifier, so both AWS-URL checks in this
 * codebase stay in lockstep — this is the exact check that stops the classic SNS mistake: an
 * attacker who controls SigningCertURL can otherwise point it at a certificate they hold the
 * private key for and sign whatever message they like.
 */
public final class AwsSnsHostValidator {

    private static final Pattern SNS_HOST_PATTERN =
        Pattern.compile("^sns\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?$");

    private AwsSnsHostValidator() {
    }

    public static boolean isValidSnsHost(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && SNS_HOST_PATTERN.matcher(uri.getHost().toLowerCase(Locale.ROOT)).matches();
        } catch (Exception e) {
            return false;
        }
    }
}
