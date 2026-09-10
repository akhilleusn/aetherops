package com.aetherops.auth.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Phase 2 remediation, finding 16 (see STEP_REPORT.md): the dev-mode fallback (no Resend API
 * key configured) used to log the live verification/reset link — including the token — at INFO.
 * That's a credential-equivalent leak if this ever runs against a real log aggregator with
 * {@code app.resend.api-key} accidentally unset in production. Now: the fallback firing, and
 * who it was for, is always logged at WARN (loud enough that a misconfigured deployment is
 * visible); the link/token itself is logged ONLY when {@code app.resend.expose-dev-links} is
 * explicitly true — a separate opt-in from "api key is unset," so enabling it requires a
 * deliberate action, not just an absent config value — and a loud WARN fires at startup
 * whenever that property is on, so it can't silently ride along into a production deploy.
 */
@Service
@Slf4j
public class EmailService {

    private final String apiKey;
    private final String fromAddress;
    private final boolean exposeDevLinks;
    private final RestTemplate restTemplate;

    public EmailService(
            @Value("${app.resend.api-key:}") String apiKey,
            @Value("${app.resend.from-address:zeytates@gmail.com}") String fromAddress,
            @Value("${app.resend.expose-dev-links:false}") boolean exposeDevLinks,
            RestTemplateBuilder builder) {
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        this.exposeDevLinks = exposeDevLinks;
        this.restTemplate = builder.build();
    }

    @PostConstruct
    void warnIfDevLinksExposed() {
        if (exposeDevLinks) {
            log.warn("app.resend.expose-dev-links is ENABLED — verification and password-reset " +
                "links (including live tokens) will be written to application logs in plaintext. " +
                "This must NEVER be enabled in a production deployment.");
        }
    }

    public void sendVerificationEmail(String to, String verifyLink) {
        if (apiKey.isBlank()) {
            log.warn("Dev-mode email fallback fired (app.resend.api-key not configured) — " +
                "no verification email sent to {}", to);
            if (exposeDevLinks) {
                log.warn("Dev-mode verification link for {}: {}", to, verifyLink);
            }
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of(
                "from", fromAddress,
                "to", List.of(to),
                "subject", "Verify your AetherOps email address",
                "html", buildVerificationHtml(verifyLink)
            );

            restTemplate.exchange(
                "https://api.resend.com/emails",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
            );
        } catch (Exception e) {
            log.error("Failed to send verification email to {}: {}", to, e.getMessage());
        }
    }

    public void sendPasswordResetEmail(String to, String resetLink) {
        if (apiKey.isBlank()) {
            log.warn("Dev-mode email fallback fired (app.resend.api-key not configured) — " +
                "no password reset email sent to {}", to);
            if (exposeDevLinks) {
                log.warn("Dev-mode password reset link for {}: {}", to, resetLink);
            }
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

    private String buildVerificationHtml(String verifyLink) {
        return "<p>Thank you for registering with AetherOps.</p>"
            + "<p><a href=\"" + verifyLink + "\">Click here to verify your email address</a></p>"
            + "<p>This link expires in <strong>24 hours</strong>.</p>"
            + "<p>If you did not create an account, you can safely ignore this email.</p>";
    }

    private String buildHtml(String resetLink) {
        return "<p>You requested a password reset for your AetherOps account.</p>"
            + "<p><a href=\"" + resetLink + "\">Click here to reset your password</a></p>"
            + "<p>This link expires in <strong>1 hour</strong>.</p>"
            + "<p>If you did not request this, you can safely ignore this email.</p>";
    }
}
