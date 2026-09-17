package com.aetherops.integrations.service;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step H2 (see STEP_REPORT.md): {@code jira-ticket}/{@code servicenow-ticket} use resilience4j's
 * {@code ThreadPoolBulkhead} (via {@code @Bulkhead(type = Bulkhead.Type.THREADPOOL)} on
 * {@link JiraApiClient}/{@link ServiceNowApiClient}) specifically because -- unlike the default
 * SEMAPHORE bulkhead type, which just limits how many callers may run concurrently on their own
 * thread -- it has a bounded queue and a real rejection policy once both the pool and queue are
 * full. This proves that mechanism directly, using resilience4j's core API (no Spring context
 * needed -- the annotation-driven wiring just configures an instance of the same class this test
 * exercises by hand, via the {@code resilience4j.thread-pool-bulkhead.instances.*} properties in
 * application.yml): once saturated, a further submission is rejected immediately with {@link
 * BulkheadFullException} rather than queueing unboundedly and silently.
 */
class TicketBulkheadSaturationTest {

    @Test
    void saturatedThreadPoolBulkhead_rejectsFurtherSubmissions_insteadOfQueueingUnboundedly() throws Exception {
        // core=1, max=1, queue=1: exactly two submissions can be in flight (one running, one
        // queued) before a third is rejected -- small numbers chosen so the test is fast and the
        // saturation point is unambiguous, not because production uses these exact values (see
        // resilience4j.thread-pool-bulkhead.instances.jira-ticket/servicenow-ticket in
        // application.yml for the real configured sizes).
        ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
            .coreThreadPoolSize(1)
            .maxThreadPoolSize(1)
            .queueCapacity(1)
            .build();
        ThreadPoolBulkhead bulkhead = ThreadPoolBulkhead.of("test-ticket-bulkhead", config);

        CountDownLatch blockFirstTask = new CountDownLatch(1);
        CountDownLatch firstTaskStarted = new CountDownLatch(1);

        // Occupies the pool's one thread until released.
        bulkhead.submit(() -> {
            firstTaskStarted.countDown();
            blockFirstTask.await(5, TimeUnit.SECONDS);
            return null;
        });
        assertThat(firstTaskStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // Fills the one queue slot -- accepted, but not yet running.
        bulkhead.submit(() -> null);

        // Pool full AND queue full: this must be rejected immediately, not queued indefinitely.
        assertThatThrownBy(() -> bulkhead.submit(() -> null))
            .isInstanceOf(BulkheadFullException.class);

        blockFirstTask.countDown();
    }
}
