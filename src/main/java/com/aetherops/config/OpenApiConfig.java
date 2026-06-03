package com.aetherops.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aetherOpsOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("AetherOps API")
                .description("AI-assisted incident investigation for backend teams — evidence-backed analysis, safe runbook suggestions, structured postmortem drafts, and per-tenant usage metering. AI provider: mock-v1 (no external API calls).")
                .version("v0.1.0")
                // TODO: add API key security scheme when auth is introduced
                .contact(new Contact()
                    .name("AetherOps")
                    .email("zeytates@gmail.com")));
    }
}
