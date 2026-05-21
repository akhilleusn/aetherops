package com.aetherops.analysis.config;

import com.aetherops.analysis.service.AIProvider;
import com.aetherops.analysis.service.AnthropicAIProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Bean
    @Primary
    @Conditional(AnthropicKeyPresent.class)
    public AIProvider anthropicAIProvider(ObjectMapper objectMapper) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        return new AnthropicAIProvider(apiKey, RestClient.create(), objectMapper);
    }
}
