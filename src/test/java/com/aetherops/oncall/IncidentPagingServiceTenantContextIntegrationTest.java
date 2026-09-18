package com.aetherops.oncall;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.oncall.OnCallService.PageOutcome;
import com.aetherops.oncall.OnCallService.PageResult;
import com.aetherops.push.PushNotificationService;
import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step H2 follow-up (see STEP_REPORT.md). {@code IncidentPagingServiceTest} (the Mockito-only
 * suite) mocks {@link AlertDeduplication} entirely, so nothing in that suite would notice if
 * {@code IncidentPagingService.maybePage}'s {@code TenantContext.set(tenantId)} were removed —
 * the mock doesn't care what {@code TenantContext} holds when it's invoked. This test uses a
 * real {@link AlertDeduplication} bean backed by a real {@link AlertDeduplicationRepository}
 * against real Postgres with RLS enforced (via {@link AbstractIntegrationTest}, non-superuser
 * {@code app_user} role, {@code FORCE ROW LEVEL SECURITY}), so it actually exercises the
 * INSERT that Step H2's recon traced through the RLS SQL: with {@code app.tenant_id} unset,
 * that INSERT is rejected by the {@code deduplicated_alerts} table's RLS policy, the exception
 * propagates out of the {@code @Async void} method to Spring's default async-exception handler
 * (logged, dropped), and {@code onCallService.pageOnCall(...)} is never reached.
 *
 * <p>{@code maybePage} is genuinely {@code @Async("pagingExecutor")} here (a real
 * {@code @SpringBootTest} context, not a plain unit test where the annotation is inert) — the
 * assertions below use {@code Mockito.verify(..., timeout(...))} to wait for the real async
 * dispatch to complete, and deliberately do NOT call {@code TenantContext.set(...)} on the test's
 * own thread first, since doing so would prove nothing: the whole point is whether the fix
 * inside {@code maybePage} itself, running on the {@code pagingExecutor} thread, is what makes
 * this work.
 *
 * <p>Removal verification performed for this specific change (see STEP_REPORT.md for both
 * results): {@code TenantContext.set(tenantId)} was commented out in
 * {@code IncidentPagingService.maybePage}, this test was run and observed to fail, the line was
 * restored, and the test was run again and observed to pass.
 */
@ActiveProfiles("test")
class IncidentPagingServiceTenantContextIntegrationTest extends AbstractIntegrationTest {

    @Autowired private IncidentPagingService pagingService;
    @Autowired private AlertDeduplication alertDeduplication;
    @Autowired private AlertDeduplicationRepository alertDeduplicationRepository;
    @Autowired private TenantRepository tenantRepository;

    @MockitoBean private OnCallService onCallService;
    @MockitoBean private PushNotificationService pushNotificationService;
    @MockitoBean private IncidentTimelineService timelineService;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void maybePage_realAlertDeduplication_getsPastRecordCall_reachesPageAttempt() {
        UUID tenantId = createTenant();
        UUID incidentId = UUID.randomUUID();
        when(onCallService.pageOnCall(any(), any(), any()))
            .thenReturn(new PageResult(PageOutcome.ACCEPTED, "SM123", "queued"));
        when(pushNotificationService.sendPageAlert(any(), any(), any())).thenReturn(true);

        pagingService.maybePage(tenantId, incidentId, "db-svc", "DB down", IncidentSeverity.CRITICAL);

        // This is the assertion that would fail (time out) if TenantContext.set() were removed
        // from maybePage: AlertDeduplication.record()'s real INSERT would be rejected by RLS
        // before onCallService is ever called.
        verify(onCallService, timeout(5000)).pageOnCall(tenantId, "db-svc", "DB down");
        verify(pushNotificationService, timeout(5000)).sendPageAlert(any(), any(), any());

        // Direct proof the INSERT actually succeeded under RLS, not just an inference from the
        // mock being reached: read the real row back under a correctly-scoped tenant context.
        String fingerprint = alertDeduplication.fingerprint(new AlertDeduplication.Alert(
            tenantId, "db-svc", "DB down", SeverityLevel.P1));
        List<DeduplicatedAlert> rows = withTenant(tenantId,
            () -> alertDeduplicationRepository.findByTenantIdAndFingerprint(tenantId, fingerprint).stream().toList());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getIncidentId()).isEqualTo(incidentId);
    }

    private UUID createTenant() {
        return tenantRepository.save(Tenant.builder()
            .name("Paging TenantContext Test " + UUID.randomUUID())
            .plan(Plan.STARTER)
            .aiMonthlyBudgetCents(0)
            .retentionDays(30)
            .build()).getId();
    }
}
