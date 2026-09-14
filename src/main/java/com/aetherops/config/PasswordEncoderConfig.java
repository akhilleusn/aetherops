package com.aetherops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    /**
     * Phase 3 crypto cleanup (see STEP_REPORT.md): bumped from the no-arg default (strength 10)
     * to 12. BCrypt embeds its cost factor in the hash itself ({@code $2a$<cost>$...}), so
     * existing strength-10 hashes keep verifying unchanged under this encoder — only newly
     * hashed passwords cost more to compute.
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
