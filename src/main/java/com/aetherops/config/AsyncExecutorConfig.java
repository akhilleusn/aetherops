package com.aetherops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Step H2 (see STEP_REPORT.md): every {@code @Async} method in this codebase (Slack
 * notifications, webhook delivery, paging) previously shared Spring Boot's one autoconfigured
 * default executor -- core size 8, queue capacity effectively unbounded ({@code
 * Integer.MAX_VALUE}). Because the queue never fills, the pool never grows past 8 threads and
 * never rejects either: a hanging call in any one of those three areas just piles further tasks
 * from ALL THREE into one ever-growing queue behind the stuck threads, with no isolation and no
 * backpressure signal.
 *
 * <p>This gives each area its own small, named, bounded-queue executor -- a hanging Jira/Slack/
 * webhook/Twilio call can no longer starve the other two. Each queue is bounded and paired with
 * {@link ThreadPoolExecutor.AbortPolicy} (Spring's own default, made explicit here) rather than
 * left to queue forever: once saturated, a task is rejected (and logged via Spring's default
 * {@code AsyncUncaughtExceptionHandler}) instead of queueing silently and indefinitely.
 */
@Configuration
public class AsyncExecutorConfig {

    @Bean("pagingExecutor")
    public Executor pagingExecutor(
            @Value("${app.async.paging.core-pool-size}") int coreSize,
            @Value("${app.async.paging.max-pool-size}") int maxSize,
            @Value("${app.async.paging.queue-capacity}") int queueCapacity) {
        return buildExecutor("paging-", coreSize, maxSize, queueCapacity);
    }

    @Bean("slackExecutor")
    public Executor slackExecutor(
            @Value("${app.async.slack.core-pool-size}") int coreSize,
            @Value("${app.async.slack.max-pool-size}") int maxSize,
            @Value("${app.async.slack.queue-capacity}") int queueCapacity) {
        return buildExecutor("slack-", coreSize, maxSize, queueCapacity);
    }

    @Bean("webhookExecutor")
    public Executor webhookExecutor(
            @Value("${app.async.webhook.core-pool-size}") int coreSize,
            @Value("${app.async.webhook.max-pool-size}") int maxSize,
            @Value("${app.async.webhook.queue-capacity}") int queueCapacity) {
        return buildExecutor("webhook-", coreSize, maxSize, queueCapacity);
    }

    private static ThreadPoolTaskExecutor buildExecutor(String threadNamePrefix, int coreSize, int maxSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
