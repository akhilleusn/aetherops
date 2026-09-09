package com.aetherops.integrations.webhook;

import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Phase 1 Step E, item 2 (finding 5) migration for tenants that existed before per-provider
 * static-token secrets did: rather than leaving every existing Datadog/New Relic/Opsgenie/
 * Rollbar/Alertmanager integration permanently unauthenticated beyond the shared X-Api-Key,
 * generate a real secret for each on startup and give the tenant a grace period to actually
 * start sending it before enforcement begins.
 *
 * <p>Off by default ({@code app.webhooks.migration.backfill-legacy-secrets: false}) and meant
 * to be run exactly once against production data, then turned back off — this is a one-time
 * backfill, not something that should re-evaluate on every deploy. Idempotent regardless:
 * {@link WebhookSigningSecretService#backfillIfAbsent} never touches a (tenant, provider) row
 * that already exists, so leaving the flag on or re-running it is harmless, just wasted work.
 *
 * <p><b>Distribution — secrets are never logged.</b> A generated value is live credential
 * material; writing it through the logger would ship it into whatever log aggregation this
 * deployment forwards to, retained indefinitely and readable by anyone with log access — a
 * credential leak, not a distribution mechanism. Instead, this runner requires the operator to
 * choose one of two explicit outputs before it will do anything:
 * <ul>
 *   <li>{@code app.webhooks.migration.secrets-output-path} — generated secrets are appended, in
 *       plaintext, to this file, which is created (if it doesn't already exist) with owner-only
 *       permissions ({@code rw-------} via POSIX ACLs, or the closest {@code File} can enforce on
 *       a non-POSIX filesystem) — see {@link #createOwnerOnlyFile}. Only the resulting secret
 *       count and file path are logged, never the values.</li>
 *   <li>{@code --print-secrets} (a command-line argument, not a config property) — if a file
 *       genuinely isn't viable for this deployment, secrets are written directly to STDOUT
 *       (never through the logger, so they don't flow into whatever sink the logger is
 *       configured with) and a WARN is logged that this happened and how many. This is a
 *       conscious, one-time tradeoff the operator opts into per invocation, not a default.</li>
 * </ul>
 * If neither is given, the runner logs an error and does nothing — generating a secret this
 * runner then has no way to hand back to the operator would make it permanently undistributable
 * (it's stored encrypted, not held in memory anywhere after this method returns), which is worse
 * than not generating it at all.
 *
 * <p>There is still no self-service secrets UI or vault integration in this codebase (the same
 * gap already flagged in STEP_REPORT.md for the four HMAC providers) — the file/stdout output
 * above is a stopgap for getting a value out of this runner, not a secure long-term home for it.
 * The operator is expected to move each secret from the output file to wherever they actually
 * hand it to the tenant (support channel, tenant dashboard, etc.), then delete/rotate the output
 * file's access once done. A real secrets-distribution mechanism (self-service rotation UI,
 * tenant-visible integration status, or a proper secrets-vault handoff) is follow-up work this
 * migration cannot substitute for.
 *
 * <p><b>Why this closes the "unauthenticated" gap without breaking anyone on deploy.</b> A
 * newly backfilled row is created with {@code gracePeriodEndsAt} in the future; until then,
 * {@link WebhookSignatureFilter} treats a missing/invalid static token on that (tenant,
 * provider) exactly like "no secret configured" — a logged fallback to the existing
 * X-Api-Key-only auth, not a 401 (see the entity/migration javadoc). Nothing this migration
 * does changes any tenant's behavior the moment it runs; enforcement only begins once the grace
 * period elapses, by which point the operator is expected to have distributed the secret and
 * the tenant to have configured their sending side to include it.
 *
 * <p>Deliberately limited to the five providers a static-token mechanism exists for at all
 * (see {@link WebhookSigningProvider}'s javadoc) — NOT GitHub/PagerDuty/Grafana. Those are real
 * HMAC secrets the tenant already owns and configured on their sending side (e.g. the secret
 * typed into a GitHub webhook's settings); a randomly generated value here would never match
 * what the sender actually signs with, unlike a static token, which starts working the moment
 * the tenant copies the generated value into their sending side's custom-header (or, for
 * Rollbar, query-param) configuration. Splunk is excluded entirely — no mechanism exists to
 * carry a secret through it at all, see the comment on {@code IntegrationsWebhookController.splunk(...)}.
 */
@Component
@ConditionalOnProperty(name = "app.webhooks.migration.backfill-legacy-secrets", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class LegacyWebhookSecretBackfillRunner implements ApplicationRunner {

    private static final List<WebhookSigningProvider> BACKFILL_PROVIDERS = List.of(
        WebhookSigningProvider.DATADOG, WebhookSigningProvider.NEW_RELIC,
        WebhookSigningProvider.OPSGENIE, WebhookSigningProvider.ROLLBAR,
        WebhookSigningProvider.ALERTMANAGER
    );

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRepository tenantRepository;
    private final WebhookSigningSecretService signingSecretService;

    @Value("${app.webhooks.migration.grace-period-days:30}")
    private long gracePeriodDays;

    /** Blank (the default) means "not configured" — see class javadoc on why that, combined
     *  with no --print-secrets flag, makes this runner refuse to do anything. */
    @Value("${app.webhooks.migration.secrets-output-path:}")
    private String secretsOutputPath;

    private record BackfilledSecret(java.util.UUID tenantId, WebhookSigningProvider provider,
                                     String secret, Instant graceEndsAt) {}

    @Override
    public void run(ApplicationArguments args) {
        boolean printSecrets = args.containsOption("print-secrets");
        boolean hasOutputPath = secretsOutputPath != null && !secretsOutputPath.isBlank();

        if (!hasOutputPath && !printSecrets) {
            log.error("app.webhooks.migration.backfill-legacy-secrets is enabled, but neither "
                + "app.webhooks.migration.secrets-output-path nor the --print-secrets flag was given. "
                + "Refusing to run: a secret generated here with no configured way to retrieve it "
                + "would be permanently undistributable (it's stored encrypted, never held in memory "
                + "after this method returns) — that's worse than not generating it at all. Set the "
                + "output path, or pass --print-secrets, and re-run.");
            return;
        }

        Instant gracePeriodEndsAt = Instant.now().plus(gracePeriodDays, ChronoUnit.DAYS);
        List<BackfilledSecret> generated = new ArrayList<>();
        long tenantCount = 0;

        for (Tenant tenant : tenantRepository.findAll()) {
            tenantCount++;
            TenantContext.set(tenant.getId());
            try {
                for (WebhookSigningProvider provider : BACKFILL_PROVIDERS) {
                    String secret = generateSecret();
                    boolean wasCreated = signingSecretService.backfillIfAbsent(
                        tenant.getId(), provider, secret, gracePeriodEndsAt);
                    if (wasCreated) {
                        generated.add(new BackfilledSecret(tenant.getId(), provider, secret, gracePeriodEndsAt));
                    }
                }
            } finally {
                TenantContext.clear();
            }
        }

        if (generated.isEmpty()) {
            log.info("Legacy webhook secret backfill: no new secrets needed across {} tenant(s) "
                + "(already backfilled, or none exist).", tenantCount);
            return;
        }

        if (hasOutputPath) {
            Path path = Path.of(secretsOutputPath);
            try {
                writeSecretsFile(path, generated);
                log.info("Legacy webhook secret backfill complete: generated {} new secret(s) across {} "
                        + "tenant(s), grace period ends {} -- written to {} (owner-only permissions). Secret "
                        + "values are never logged; distribute them from that file, then remove/rotate access "
                        + "to it once done.",
                    generated.size(), tenantCount, gracePeriodEndsAt, path.toAbsolutePath());
            } catch (IOException e) {
                log.error("Generated {} new secret(s) but FAILED to write them to {} -- they exist (encrypted) "
                        + "in inbound_webhook_signing_secrets but this runner cannot recover them in "
                        + "plaintext after the fact. Fix the output path/permissions and re-run; "
                        + "already-created rows are left untouched (backfillIfAbsent is idempotent), so "
                        + "re-running will simply have nothing new to do for them and those specific secrets "
                        + "are lost — they'll need to be reconfigured via WebhookSigningSecretService.configure(...).",
                    generated.size(), path.toAbsolutePath(), e);
            }
        } else {
            log.warn("--print-secrets was passed: printing {} generated webhook secret value(s) to STDOUT now "
                + "(not through the logger, so they don't flow into log aggregation). This is sensitive "
                + "output -- capture it directly from the console, then clear your terminal scrollback/history.",
                generated.size());
            for (BackfilledSecret s : generated) {
                System.out.println(s.tenantId() + "," + s.provider() + "," + s.secret() + "," + s.graceEndsAt());
            }
            log.info("Legacy webhook secret backfill complete: generated {} new secret(s) across {} tenant(s), "
                + "grace period ends {} -- printed to stdout per --print-secrets; not written to a file, "
                + "not logged.", generated.size(), tenantCount, gracePeriodEndsAt);
        }
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void writeSecretsFile(Path path, List<BackfilledSecret> secrets) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean isNewFile = !Files.exists(absolute);
        if (isNewFile) {
            createOwnerOnlyFile(absolute);
        }

        StringBuilder csv = new StringBuilder();
        if (isNewFile) {
            csv.append("tenant_id,provider,secret,grace_period_ends_at").append(System.lineSeparator());
        }
        for (BackfilledSecret s : secrets) {
            csv.append(s.tenantId()).append(',').append(s.provider()).append(',')
                .append(s.secret()).append(',').append(s.graceEndsAt()).append(System.lineSeparator());
        }
        Files.writeString(absolute, csv.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        if (!isNewFile) {
            // Appending to a file from a previous run — re-assert restrictive permissions in
            // case anything else touched it in between, rather than trusting it's still locked
            // down.
            restrictToOwnerOnly(absolute);
        }
    }

    /** Creates the file with owner-only permissions atomically where the filesystem supports it
     *  (POSIX ACLs), so it's never briefly world/group-readable between creation and permission-
     *  setting. Falls back to create-then-restrict on a non-POSIX filesystem (e.g. Windows in a
     *  local/dev environment; production targets a POSIX host). */
    private void createOwnerOnlyFile(Path path) throws IOException {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(path);
            restrictToOwnerOnly(path);
        }
    }

    private void restrictToOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            File file = path.toFile();
            boolean restricted = file.setReadable(false, false) & file.setReadable(true, true)
                & file.setWritable(false, false) & file.setWritable(true, true)
                & file.setExecutable(false, false);
            if (!restricted) {
                log.warn("Could not confirm owner-only permissions on {} on this filesystem (not POSIX, "
                    + "and at least one java.io.File permission call reported failure) -- verify manually "
                    + "before leaving this secrets file in place.", path);
            }
        } catch (IOException e) {
            log.warn("Failed to set owner-only permissions on {} -- verify manually.", path, e);
        }
    }
}
