package com.aetherops.slack;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Step H2 follow-up (see STEP_REPORT.md). {@code IncidentSlackNotificationServiceTest} (the
 * Mockito-only suite) mocks {@link IncidentRepository}, so {@code verify(incidentRepository)
 * .save(...)} only proves {@code save()} was CALLED with the right in-memory object — it proves
 * nothing about whether that write would actually persist against a real RLS-protected
 * {@code incidents} table. This test uses a real {@link IncidentRepository} against real
 * Postgres with RLS enforced (via {@link AbstractIntegrationTest}), and re-reads the incident
 * from the database afterward — under a correctly-scoped tenant context, via a fresh query, not
 * the in-memory object the service mutated — to prove the UPDATE actually persisted.
 *
 * <p>{@code notify} is genuinely {@code @Async("slackExecutor")} here (a real
 * {@code @SpringBootTest} context), and the test deliberately never calls
 * {@code TenantContext.set(...)} on its own thread first, for the same reason as
 * {@code IncidentPagingServiceTenantContextIntegrationTest}: the fix under test is what
 * {@code notify} itself does on the {@code slackExecutor} thread, not anything the caller does.
 *
 * <p>{@code slack_workspaces} has no RLS policy (see V60's migration comment — inbound Slack
 * webhooks resolve tenant cross-tenant from Slack's own {@code team_id} before any tenant
 * context exists), so the workspace lookup and the outbound Slack POST (mocked here) succeed
 * regardless of {@code TenantContext}. Only the {@code incidents} row write is RLS-gated, which
 * is exactly what this test isolates.
 *
 * <p>Removal verification performed for this specific change (see STEP_REPORT.md for both
 * results): {@code TenantContext.set(incident.getTenantId())} was commented out in
 * {@code IncidentSlackNotificationService.notify}, this test was run and observed to fail, the
 * line was restored, and the test was run again and observed to pass.
 */
@ActiveProfiles("test")
class IncidentSlackNotificationServiceTenantContextIntegrationTest extends AbstractIntegrationTest {

    @Autowired private IncidentSlackNotificationService notificationService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private SlackWorkspaceRepository workspaceRepository;
    @Autowired private TenantRepository tenantRepository;

    @MockitoBean private SlackConversationService conversationService;
    @MockitoBean private SlackNotifier legacyNotifier;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void notify_botTokenPathSucceeds_slackChannelAndThreadTs_actuallyPersistToDatabase() {
        UUID tenantId = createTenant();
        Incident incident = seedIncident(tenantId);
        // slack_workspaces has no RLS -- see this class's javadoc -- so a plain save is fine
        // here without withTenant().
        workspaceRepository.save(SlackWorkspace.builder()
            .tenantId(tenantId).teamId("T" + UUID.randomUUID().toString().substring(0, 8))
            .botUserId("B1").encryptedBotToken("enc-token").defaultChannelId("C1")
            .build());

        when(conversationService.postIncidentAlert(any(), any()))
            .thenReturn(Optional.of(new SlackConversationService.PostedMessage("C1", "1111.2222")));

        notificationService.notify(incident);

        // Polls the actual persisted row rather than any mock invocation. verify(mock,
        // timeout(...)) only proves a method was CALLED -- Mockito records the invocation at
        // call entry, before an unstubbed/spied method's real body (and its @Transactional
        // commit) has necessarily finished -- so it can't be used to wait for a real write to
        // land. Awaiting the actual database state is the only correct way to synchronize with
        // the async slackExecutor dispatch here. The assertion inside is what would fail (time
        // out) if TenantContext.set() were removed from notify(): a fresh read of the real row,
        // under a correctly-scoped tenant context, not the in-memory Incident object notify()
        // mutated (which reflects the Java-side setter call regardless of whether the UPDATE
        // that was supposed to persist it actually did).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Incident reloaded = withTenant(tenantId,
                () -> incidentRepository.findByIdAndTenantId(incident.getId(), tenantId))
                .orElseThrow(() -> new AssertionError("Incident not found on reload — tenant-scoped read itself failed"));

            assertThat(reloaded.getSlackChannelId()).isEqualTo("C1");
            assertThat(reloaded.getSlackThreadTs()).isEqualTo("1111.2222");
        });
    }

    private UUID createTenant() {
        return tenantRepository.save(Tenant.builder()
            .name("Slack TenantContext Test " + UUID.randomUUID())
            .plan(Plan.STARTER)
            .aiMonthlyBudgetCents(0)
            .retentionDays(30)
            .build()).getId();
    }

    private Incident seedIncident(UUID tenantId) {
        Instant now = Instant.now();
        return withTenant(tenantId, () -> incidentRepository.save(Incident.builder()
            .tenantId(tenantId)
            .title("Slack TenantContext test incident")
            .status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.CRITICAL)
            .serviceId("slack-tc-test-svc")
            .fingerprint(UUID.randomUUID().toString())
            .eventCount(1)
            .firstSeenAt(now)
            .lastSeenAt(now)
            .build()));
    }
}
