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
                .description("AI-assisted backend reliability platform — log ingestion, incident management, and secret masking.")
                .version("v0.1.0")
                // TODO: add API key security scheme when auth is introduced
                .contact(new Contact()
                    .name("AetherOps")
                    .email("ops@aetherops.io")));
    }
}
