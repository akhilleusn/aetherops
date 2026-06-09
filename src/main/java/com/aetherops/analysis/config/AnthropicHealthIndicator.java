package com.aetherops.analysis.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component("anthropicAi")
@Slf4j
public class AnthropicHealthIndicator implements HealthIndicator {

    private final String apiKey;
    private final RestClient restClient;

    public AnthropicHealthIndicator(
            @Value("${anthropic.api-key:}") String apiKey,
            RestClient.Builder restClientBuilder) {
        this.apiKey = apiKey;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public Health health() {
        if (apiKey == null || apiKey.isBlank()) {
            return Health.unknown()
                    .withDetail("reason", "API key not configured")
                    .build();
        }

        try {
            restClient.get()
                    .uri("https://api.anthropic.com/v1/models")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .retrieve()
                    .toBodilessEntity();

            return Health.up()
                    .withDetail("provider", "Anthropic")
                    .withDetail("endpoint", "api.anthropic.com")
                    .build();

        } catch (Exception e) {
            return Health.down()
                    .withDetail("provider", "Anthropic")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}