package com.aetherops.analysis.config;

import com.aetherops.analysis.service.AIProvider;
import com.aetherops.analysis.service.AnthropicAIProvider;
import com.aetherops.config.RestClientTimeoutConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AIProviderConfig {

    static class AnthropicKeyPresent implements Condition {
        @Override
        public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata meta) {
            String key = ctx.getEnvironment().getProperty("ANTHROPIC_API_KEY");
            return key != null && !key.isBlank();
        }
    }

    /**
     * Step H2 (see STEP_REPORT.md): bug found while implementing, not part of the original
     * finding -- this used {@code RestClient.create()}, a bare static factory that bypasses
     * Spring Boot's {@code RestClient.Builder} autoconfiguration entirely (and therefore every
     * {@code RestClientCustomizer} bean, including the default connect/read timeout this batch
     * adds in {@code RestClientTimeoutConfig}). The actual Anthropic API call this powers had no
     * timeout at all, independent of the {@code anthropic-ai} resilience4j circuit breaker/
     * bulkhead/retry already wrapping it -- those bound concurrency and retries, not the socket
     * itself; a single hung call still occupied a bulkhead slot for as long as the OS allowed.
     * Injecting the builder (same as {@link #anthropicHealthIndicator}, just below, already
     * does) picks up the timeout for free.
     *
     * <p>The read timeout is then overridden with {@code app.anthropic.read-timeout-ms}: the
     * shared 3s default is shorter than any real Messages response (the model thinks before it
     * answers), so every analysis timed out. The connect timeout stays the shared default.
     */
    @Bean
    @Primary
    @Conditional(AnthropicKeyPresent.class)
    public AIProvider anthropicAIProvider(
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            @Value("${app.http-client.default-connect-timeout-ms}") long connectTimeoutMs,
            @Value("${app.anthropic.read-timeout-ms}") long readTimeoutMs) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        return new AnthropicAIProvider(apiKey,
            anthropicRestClient(restClientBuilder, connectTimeoutMs, readTimeoutMs), objectMapper);
    }

    // package-private for unit testing
    static RestClient anthropicRestClient(RestClient.Builder builder, long connectTimeoutMs, long readTimeoutMs) {
        return builder
            .requestFactory(RestClientTimeoutConfig.buildFactory(
                Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(readTimeoutMs)))
            .build();
    }

    /** Only registered when ANTHROPIC_API_KEY is present — avoids phantom UNKNOWN in health. */
    @Bean("anthropicAi")
    @Conditional(AnthropicKeyPresent.class)
    public HealthIndicator anthropicHealthIndicator(RestClient.Builder builder) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        return new AnthropicHealthIndicator(apiKey, builder);
    }
}
