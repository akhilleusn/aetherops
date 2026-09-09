package com.aetherops.integrations.webhook;

import com.aetherops.apikey.model.ApiKey;
import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.common.exception.InvalidApiKeyException;
import com.aetherops.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 1 Step E, item 2 (finding 5): verifies provider request signatures for the three
 * inbound alert webhooks with a confirmed, documented HMAC signing scheme (GitHub,
 * PagerDuty, Grafana) — approved for implementation after E2 recon. Sentry, VictorOps and
 * Sumo Logic are explicitly NOT handled here (unconfirmed schemes; see STEP_REPORT.md).
 * AWS SNS (cloudwatch) is verified separately by SnsSignatureVerifier, called from the
 * controller, since its scheme is certificate-based rather than a shared secret checked
 * here.
 *
 * OPT-IN, per tenant: if no enabled WebhookSigningSecret row exists for (tenant, provider),
 * this filter does nothing but log a WARN and let the request through to the existing
 * X-Api-Key check — signing is something each tenant turns on for their own integration,
 * not something this codebase can require of every sender. Once a secret IS configured for
 * a tenant's provider, a missing or invalid signature is rejected outright: the presence of
 * a secret means signing is expected, so silently accepting an unsigned request at that
 * point would defeat the point of configuring one.
 *
 * Runs before the request body is parsed (see CachedBodyHttpServletRequest) — verification
 * happens against the exact raw bytes, never a re-serialized DTO.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookSignatureFilter extends OncePerRequestFilter {

    private static final Map<String, WebhookSigningProvider> PROTECTED_PATHS = Map.ofEntries(
        Map.entry("/api/v1/integrations/github", WebhookSigningProvider.GITHUB),
        Map.entry("/api/v1/integrations/pagerduty", WebhookSigningProvider.PAGERDUTY),
        Map.entry("/api/v1/integrations/grafana", WebhookSigningProvider.GRAFANA),
        Map.entry("/api/v1/integrations/new-relic", WebhookSigningProvider.NEW_RELIC),
        Map.entry("/api/v1/integrations/opsgenie", WebhookSigningProvider.OPSGENIE),
        Map.entry("/api/v1/integrations/rollbar", WebhookSigningProvider.ROLLBAR),
        Map.entry("/api/v1/monitoring/datadog", WebhookSigningProvider.DATADOG),
        Map.entry("/api/v1/monitoring/prometheus/alertmanager", WebhookSigningProvider.ALERTMANAGER)
    );

    /** These sign the raw body — verification needs CachedBodyHttpServletRequest. The rest
     *  (STATIC_TOKEN) check a header or query param only; the body is never touched. */
    private static final Set<WebhookSigningProvider> HMAC_PROVIDERS = Set.of(
        WebhookSigningProvider.GITHUB, WebhookSigningProvider.PAGERDUTY, WebhookSigningProvider.GRAFANA
    );

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private static final String GITHUB_DEFAULT_HEADER = "X-Hub-Signature-256";
    private static final String PAGERDUTY_DEFAULT_HEADER = "X-PagerDuty-Signature";
    private static final String GRAFANA_DEFAULT_HEADER = "X-Grafana-Alerting-Signature";

    /** Default header for STATIC_TOKEN providers other than Rollbar. Not provider-mandated
     *  like Grafana's signature header — these providers don't dictate a header name, the
     *  tenant configures whatever their sending side lets them send, so one fixed default
     *  name (overridable via WebhookSigningSecret.headerName, same field Grafana uses) is
     *  simplest. */
    private static final String STATIC_TOKEN_DEFAULT_HEADER = "X-Webhook-Secret";

    /** Rollbar's own documented convention is a URL query token, e.g. ".../poke?auth=..." —
     *  not a header at all. */
    private static final String ROLLBAR_QUERY_PARAM = "auth";

    private final ApiKeyService apiKeyService;
    private final WebhookSigningSecretService signingSecretService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !PROTECTED_PATHS.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        WebhookSigningProvider provider = PROTECTED_PATHS.get(request.getRequestURI());
        boolean requiresRawBody = HMAC_PROVIDERS.contains(provider);
        HttpServletRequest downstreamRequest = requiresRawBody ? new CachedBodyHttpServletRequest(request) : request;

        String rawKey = request.getHeader("X-Api-Key");
        if (rawKey == null || rawKey.isBlank()) {
            // No key at all — let the existing ApiKeyAuthFilter / controller reject this the
            // same way it always has. There's no tenant to look a signing secret up for yet.
            chain.doFilter(downstreamRequest, response);
            return;
        }

        ApiKey apiKey;
        try {
            apiKey = apiKeyService.resolve(rawKey);
        } catch (InvalidApiKeyException e) {
            chain.doFilter(downstreamRequest, response);
            return;
        }

        TenantContext.set(apiKey.getTenantId());
        try {
            Optional<WebhookSigningSecret> configured = signingSecretService.find(apiKey.getTenantId(), provider);
            if (configured.isEmpty()) {
                log.warn("No {} signing secret configured for tenantId={} — falling back to API-key-only auth",
                    provider, apiKey.getTenantId());
                chain.doFilter(downstreamRequest, response);
                return;
            }

            String secret = signingSecretService.decrypt(configured.get());
            boolean valid;
            if (requiresRawBody) {
                byte[] rawBody = ((CachedBodyHttpServletRequest) downstreamRequest).getCachedBody();
                valid = switch (provider) {
                    case GITHUB -> verifyGitHub(request, rawBody, secret);
                    case PAGERDUTY -> verifyPagerDuty(request, rawBody, secret);
                    case GRAFANA -> verifyGrafana(request, rawBody, secret, configured.get());
                    default -> throw new IllegalStateException("Unexpected HMAC provider: " + provider);
                };
            } else {
                valid = verifyStaticToken(request, provider, secret, configured.get());
            }

            if (!valid) {
                Instant graceEndsAt = configured.get().getGracePeriodEndsAt();
                if (graceEndsAt != null && Instant.now().isBefore(graceEndsAt)) {
                    // This secret was generated FOR the tenant by the existing-tenant migration
                    // (LegacyWebhookSecretBackfillRunner), not configured by them yet — a
                    // manually configured secret never has a grace period (see V66/the entity
                    // javadoc), so this branch can only be reached during that transition
                    // window. Treat it the same as "no secret configured" until the tenant has
                    // had a chance to actually add it to their sending side, or until it expires.
                    log.warn("{} webhook signature missing/invalid for tenantId={}, but a migration-generated "
                        + "secret is still within its grace period (ends {}) — falling back to API-key-only "
                        + "auth rather than rejecting", provider, apiKey.getTenantId(), graceEndsAt);
                    chain.doFilter(downstreamRequest, response);
                    return;
                }
                log.warn("Rejected {} webhook request with missing/invalid signature tenantId={}",
                    provider, apiKey.getTenantId());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }

            chain.doFilter(downstreamRequest, response);
        } finally {
            TenantContext.clear();
        }
    }

    /** DATADOG/NEW_RELIC/OPSGENIE/ALERTMANAGER check a header (default X-Webhook-Secret,
     *  overridable per WebhookSigningSecret.headerName); ROLLBAR checks its own documented
     *  URL query parameter instead, per that provider's convention. Either way this is a
     *  direct constant-time string compare — there is no signature to compute, only a shared
     *  secret to match. */
    private boolean verifyStaticToken(HttpServletRequest request, WebhookSigningProvider provider,
                                       String secret, WebhookSigningSecret config) {
        String presented;
        if (provider == WebhookSigningProvider.ROLLBAR) {
            presented = request.getParameter(ROLLBAR_QUERY_PARAM);
        } else {
            String headerName = config.getHeaderName() != null ? config.getHeaderName() : STATIC_TOKEN_DEFAULT_HEADER;
            presented = request.getHeader(headerName);
        }
        if (presented == null || presented.isBlank()) {
            return false;
        }
        return constantTimeEquals(secret, presented);
    }

    private boolean verifyGitHub(HttpServletRequest request, byte[] rawBody, String secret) {
        String header = request.getHeader(GITHUB_DEFAULT_HEADER);
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        String expected = "sha256=" + hmacHex(secret, rawBody);
        return constantTimeEquals(expected, header);
    }

    private boolean verifyPagerDuty(HttpServletRequest request, byte[] rawBody, String secret) {
        String header = request.getHeader(PAGERDUTY_DEFAULT_HEADER);
        if (header == null || header.isBlank()) {
            return false;
        }
        // During secret rotation PagerDuty can send multiple comma-separated v1=<hex> values
        // (old + new secret) in the same header — accept if ANY of them matches. A
        // single-value comparison silently starts rejecting every request the moment
        // PagerDuty rotates the secret.
        String expected = "v1=" + hmacHex(secret, rawBody);
        for (String candidate : header.split(",")) {
            if (constantTimeEquals(expected, candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean verifyGrafana(HttpServletRequest request, byte[] rawBody, String secret, WebhookSigningSecret config) {
        String headerName = config.getHeaderName() != null ? config.getHeaderName() : GRAFANA_DEFAULT_HEADER;
        String header = request.getHeader(headerName);
        if (header == null || header.isBlank()) {
            return false;
        }

        String timestampHeaderName = config.getTimestampHeaderName();
        String payloadToSign;
        if (timestampHeaderName != null && !timestampHeaderName.isBlank()) {
            String timestampHeader = request.getHeader(timestampHeaderName);
            if (timestampHeader == null) {
                return false;
            }
            long timestamp;
            try {
                timestamp = Long.parseLong(timestampHeader.trim());
            } catch (NumberFormatException e) {
                return false;
            }
            if (Math.abs(Instant.now().getEpochSecond() - timestamp) > MAX_CLOCK_SKEW_SECONDS) {
                log.warn("Rejected Grafana webhook: timestamp outside allowed skew");
                return false;
            }
            payloadToSign = timestampHeader.trim() + ":" + new String(rawBody, StandardCharsets.UTF_8);
        } else {
            payloadToSign = new String(rawBody, StandardCharsets.UTF_8);
        }

        String expected = hmacHex(secret, payloadToSign.getBytes(StandardCharsets.UTF_8));
        return constantTimeEquals(expected, header);
    }

    private String hmacHex(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute webhook signature", e);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8));
    }
}
