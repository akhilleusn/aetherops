package com.aetherops.analysis.config;

import com.aetherops.analysis.service.AIProvider;
import com.aetherops.analysis.service.AnthropicAIProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.client.RestClient;

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
     */
    @Bean
    @Primary
    @Conditional(AnthropicKeyPresent.class)
    public AIProvider anthropicAIProvider(ObjectMapper objectMapper, RestClient.Builder restClientBuilder) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        return new AnthropicAIProvider(apiKey, restClientBuilder.build(), objectMapper);
    }

    /** Only registered when ANTHROPIC_API_KEY is present — avoids phantom UNKNOWN in health. */
    @Bean("anthropicAi")
    @Conditional(AnthropicKeyPresent.class)
    public HealthIndicator anthropicHealthIndicator(RestClient.Builder builder) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        return new AnthropicHealthIndicator(apiKey, builder);
    }
}
