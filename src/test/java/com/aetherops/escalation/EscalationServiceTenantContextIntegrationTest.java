package com.aetherops.escalation;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.oncall.OnCallService;
import com.aetherops.oncall.OnCallService.PageOutcome;
import com.aetherops.oncall.OnCallService.PageResult;
import com.aetherops.push.PushNotificationService;
import com.aetherops.slack.SlackConversationService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Recon finding (see STEP_REPORT.md): {@code EscalationServiceTest} (the Mockito-only suite)
 * mocks {@link EscalationExecutionRecorder}, so {@code verify(executionRecorder).save(...)} only
 * proves the call happened -- it proves nothing about whether that write would actually persist
 * against the real, RLS-protected {@code escalation_executions} table (see V60). This test uses
 * the real {@link EscalationExecutionRepository} against real Postgres with RLS enforced (via
 * {@link AbstractIntegrationTest}), and reads the row back afterward under a correctly-scoped
 * tenant context, to prove the INSERT actually persisted.
 *
 * <p>{@code escalate} is genuinely {@code @Async("pagingExecutor")} here (a real
 * {@code @SpringBootTest} context), and this test deliberately never calls
 * {@code TenantContext.set(...)} on its own thread first -- the fix under test is what
 * {@code escalate} itself does on the {@code pagingExecutor} thread, and what the write it
 * triggers does once it gets there, not anything the caller does.
 *
 * <p>Removal verification performed for this specific change (see STEP_REPORT.md for both
 * results): {@code EscalationExecutionRecorder.save}'s {@code @Transactional} was commented out
 * (reproducing the original bare, self-invoked {@code executionRepository.save(execution)} call),
 * this test was run and observed to time out waiting for the row, the annotation was restored,
 * and the test was run again and observed to pass.
 */
@ActiveProfiles("test")
class EscalationServiceTenantContextIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EscalationService escalationService;
    @Autowired private EscalationExecutionRepository executionRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TenantRepository tenantRepository;

    @MockitoBean private OnCallService onCallService;
    @MockitoBean private PushNotificationService pushNotificationService;
    @MockitoBean private SlackConversationService slackConversationService;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void escalate_recordsExecution_actuallyPersistsToDatabase() {
        UUID tenantId = createTenant();
        Incident incident = seedIncident(tenantId);
        EscalationPolicy policy = EscalationPolicy.builder()
            .id(UUID.randomUUID())
            .tenantId(tenantId)
            .severityThreshold(IncidentSeverity.CRITICAL)
            .escalateAfterMinutes(5)
            .enabled(true)
            .build();

        when(onCallService.pageOnCall(any(), anyString(), anyString()))
            .thenReturn(new PageResult(PageOutcome.ACCEPTED, "SM123", null));

        escalationService.escalate(incident, policy, 1);

        // Polls the actual persisted row rather than any mock invocation -- see
        // IncidentSlackNotificationServiceTenantContextIntegrationTest's javadoc for why
        // verify(mock, timeout(...)) can't be used to wait for a real write to land. The
        // assertion inside is what would time out if EscalationExecutionRecorder.save() weren't
        // @Transactional (or if executionRepository.save were still called directly, bare, from
        // escalateInternal): a fresh tenant-scoped read of the real table, not any in-memory
        // object escalate() built.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<EscalationExecution> executions = withTenant(tenantId, () ->
                executionRepository.findByTenantIdAndIncidentIdOrderByEscalationLevelAsc(tenantId, incident.getId()));

            assertThat(executions).hasSize(1);
            assertThat(executions.get(0).getSmsStatus()).isEqualTo(EscalationExecution.ChannelStatus.ACCEPTED);
            assertThat(executions.get(0).getEscalationLevel()).isEqualTo(1);
        });
    }

    private UUID createTenant() {
        return tenantRepository.save(Tenant.builder()
            .name("Escalation TenantContext Test " + UUID.randomUUID())
            .plan(Plan.STARTER)
            .aiMonthlyBudgetCents(0)
            .retentionDays(30)
            .build()).getId();
    }

    private Incident seedIncident(UUID tenantId) {
        Instant now = Instant.now();
        return withTenant(tenantId, () -> incidentRepository.save(Incident.builder()
            .tenantId(tenantId)
            .title("Escalation TenantContext test incident")
            .status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.CRITICAL)
            .serviceId("escalation-tc-test-svc")
            .fingerprint(UUID.randomUUID().toString())
            .eventCount(1)
            .firstSeenAt(now)
            .lastSeenAt(now)
            .build()));
    }
}
