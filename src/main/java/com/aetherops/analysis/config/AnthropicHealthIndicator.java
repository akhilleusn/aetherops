package com.aetherops.analysis.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.web.client.RestClient;

@Slf4j
public class AnthropicHealthIndicator implements HealthIndicator {

    private final String apiKey;
    private final RestClient restClient;

    public AnthropicHealthIndicator(String apiKey, RestClient.Builder restClientBuilder) {
        this.apiKey = apiKey;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public Health health() {
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