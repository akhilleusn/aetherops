package com.aetherops.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class EmailService {

    private final String apiKey;
    private final String fromAddress;
    private final RestTemplate restTemplate;

    public EmailService(
            @Value("${app.resend.api-key:}") String apiKey,
            @Value("${app.resend.from-address:zeytates@gmail.com}") String fromAddress,
            RestTemplateBuilder builder) {
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        this.restTemplate = builder.build();
    }

    public void sendPasswordResetEmail(String to, String resetLink) {
        if (apiKey.isBlank()) {
            log.info("DEV MODE — password reset link for {}: {}", to, resetLink);
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of(
                "from", fromAddress,
                "to", List.of(to),
                "subject", "Reset your AetherOps password",
                "html", buildHtml(resetLink)
            );

            restTemplate.exchange(
                "https://api.resend.com/emails",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
            );
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", to, e.getMessage());
        }
    }

    private String buildHtml(String resetLink) {
        return "<p>You requested a password reset for your AetherOps account.</p>"
            + "<p><a href=\"" + resetLink + "\">Click here to reset your password</a></p>"
            + "<p>This link expires in <strong>1 hour</strong>.</p>"
            + "<p>If you did not request this, you can safely ignore this email.</p>";
    }
}
