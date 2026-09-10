package com.aetherops.auth.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 remediation, finding 16 (see STEP_REPORT.md): the dev-mode fallback must never write
 * a live verification/reset token or link to the logs unless the caller has explicitly opted
 * in via {@code app.resend.expose-dev-links}.
 */
class EmailServiceTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(EmailService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logAppender);
    }

    private List<String> loggedMessages() {
        return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void sendVerificationEmail_devMode_logsRecipientButNeverToken() {
        EmailService service = new EmailService("", "from@example.com", false, new RestTemplateBuilder());
        String verifyLink = "https://app.example.com/verify?token=super-secret-verify-token-123";

        service.sendVerificationEmail("user@example.com", verifyLink);

        List<String> messages = loggedMessages();
        assertThat(messages).anyMatch(m -> m.contains("user@example.com"));
        assertThat(messages).noneMatch(m -> m.contains(verifyLink));
        assertThat(messages).noneMatch(m -> m.contains("super-secret-verify-token-123"));
    }

    @Test
    void sendPasswordResetEmail_devMode_logsRecipientButNeverToken() {
        EmailService service = new EmailService("", "from@example.com", false, new RestTemplateBuilder());
        String resetLink = "https://app.example.com/reset?token=super-secret-reset-token-456";

        service.sendPasswordResetEmail("user2@example.com", resetLink);

        List<String> messages = loggedMessages();
        assertThat(messages).anyMatch(m -> m.contains("user2@example.com"));
        assertThat(messages).noneMatch(m -> m.contains(resetLink));
        assertThat(messages).noneMatch(m -> m.contains("super-secret-reset-token-456"));
    }

    @Test
    void sendVerificationEmail_devModeAtWarnLevel_notInfo() {
        EmailService service = new EmailService("", "from@example.com", false, new RestTemplateBuilder());

        service.sendVerificationEmail("user3@example.com", "https://app.example.com/verify?token=x");

        assertThat(logAppender.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN);
        assertThat(logAppender.list).noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO);
    }

    @Test
    void sendVerificationEmail_explicitExposeDevLinks_logsLink() {
        EmailService service = new EmailService("", "from@example.com", true, new RestTemplateBuilder());
        String verifyLink = "https://app.example.com/verify?token=explicitly-exposed-token";

        service.sendVerificationEmail("user4@example.com", verifyLink);

        assertThat(loggedMessages()).anyMatch(m -> m.contains(verifyLink));
    }

    @Test
    void startup_exposeDevLinksEnabled_logsLoudWarning() {
        EmailService service = new EmailService("", "from@example.com", true, new RestTemplateBuilder());

        service.warnIfDevLinksExposed();

        assertThat(logAppender.list)
            .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                && e.getFormattedMessage().contains("expose-dev-links")
                && e.getFormattedMessage().toLowerCase().contains("never"));
    }

    @Test
    void startup_exposeDevLinksDisabled_noWarning() {
        EmailService service = new EmailService("", "from@example.com", false, new RestTemplateBuilder());

        service.warnIfDevLinksExposed();

        assertThat(logAppender.list).isEmpty();
    }
}
