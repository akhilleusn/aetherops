package com.aetherops.integrations.service;

import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.repository.TenantTicketIntegrationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Creates a Jira issue for an incident on demand. Never wired automatically into
 * incident-creation — that would require editing the incident-service files this package
 * intentionally leaves alone — callers (e.g. TicketController) trigger it explicitly.
 *
 * <p>Step H2 (see STEP_REPORT.md): this used to run entirely on the calling Tomcat request
 * thread with no timeout — a hanging tenant-configured Jira instance pinned that thread
 * indefinitely, and enough concurrent hangs could exhaust the whole Tomcat pool. The actual HTTP
 * call now lives in {@link JiraApiClient}, behind a circuit breaker and a {@code THREADPOOL}
 * bulkhead (its own small, isolated pool). This class does the tenant-config lookup (cheap, one
 * indexed read, stays on the caller's thread) and then waits on {@code JiraApiClient}'s future
 * up to a configured bound — "moved off the request thread" chosen as bounded-wait-on-a-
 * dedicated-pool (option a from the design discussion), not true fire-and-forget async,
 * specifically to avoid changing {@code TicketController}'s response contract (callers still get
 * {@code ticketId} back synchronously, on success). True async is recorded as a follow-up, not
 * built here — it would change the API contract, which is a product decision.
 *
 * <p>Deliberately NO {@code @Retry} anywhere in this path, unlike the {@code anthropic-ai}
 * template this copies the circuit-breaker/bulkhead shape from: this creates a Jira issue via
 * POST. If a call times out after Jira already created the issue but before the response
 * reached us, a retry fires a second POST and creates a duplicate ticket. There's no idempotency
 * key in this integration to make a retry safe, so retry is left off entirely rather than copied
 * blindly from the template.
 */
@Service
@Slf4j
public class JiraTicketService {

    private final TenantTicketIntegrationRepository integrationRepository;
    private final TicketIntegrationTokenEncryptor tokenEncryptor;
    private final JiraApiClient apiClient;
    private final long callTimeoutMs;

    public JiraTicketService(
            TenantTicketIntegrationRepository integrationRepository,
            TicketIntegrationTokenEncryptor tokenEncryptor,
            JiraApiClient apiClient,
            @Value("${app.integrations.jira.call-timeout-ms}") long callTimeoutMs) {
        this.integrationRepository = integrationRepository;
        this.tokenEncryptor = tokenEncryptor;
        this.apiClient = apiClient;
        this.callTimeoutMs = callTimeoutMs;
    }

    /** Returns empty when the tenant has no (enabled) Jira config, or the API call fails — never throws. */
    public Optional<String> createIssue(UUID tenantId, UUID incidentId, String summary, String description) {
        Optional<TenantTicketIntegration> configOpt = integrationRepository
            .findByTenantIdAndProvider(tenantId, TicketProvider.JIRA)
            .filter(TenantTicketIntegration::isEnabled);
        if (configOpt.isEmpty()) {
            log.debug("No enabled Jira integration for tenantId={}, skipping issue creation", tenantId);
            return Optional.empty();
        }
        TenantTicketIntegration config = configOpt.get();

        try {
            String token = tokenEncryptor.decrypt(config.getEncryptedApiToken());
            return apiClient.createIssue(tenantId, incidentId, summary, description, config, token)
                .get(callTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Failed to create Jira issue for incidentId={} tenantId={}", incidentId, tenantId, e);
            return Optional.empty();
        }
    }
}
