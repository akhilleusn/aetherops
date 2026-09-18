package com.aetherops.config;

import com.aetherops.auth.service.TotpEncryptionProperties;
import com.aetherops.calendar.CalendarTokenEncryptionProperties;
import com.aetherops.integrations.service.TicketIntegrationTokenEncryptionProperties;
import com.aetherops.slack.SlackTokenEncryptionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds each versioned-key {@code @ConfigurationProperties} class against a real
 * {@code SystemEnvironmentPropertySource} -- the exact property source type
 * {@code StandardEnvironment} wraps {@code System.getenv()} in -- instead of injecting
 * properties directly under their canonical dotted name, which is what
 * {@code SlackTokenEncryptionPropertiesTest} (via {@code withPropertyValues}) and
 * {@code AbstractIntegrationTest}'s {@code @DynamicPropertySource} entries both do. That
 * distinction is exactly what let a real bug through both: a hyphenated multi-word
 * {@code @ConfigurationProperties} prefix segment (e.g. the former {@code token-encryption})
 * cannot be discovered when Spring enumerates SCREAMING_SNAKE_CASE environment variable
 * names to find a Map property's children -- every underscore is read as a new dotted
 * segment, and there is no way to tell from a raw env var name alone that "TOKEN_ENCRYPTION"
 * should collapse into one hyphenated word rather than two nested ones.
 */
class VersionedEncryptionKeyEnvVarBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class));

    private ApplicationContextRunner withRealEnvVars(Map<String, Object> envVars) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources()
            .addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, envVars)));
    }

    @Test
    void slackTokenEncryptionKeysBindFromDocumentedEnvVarNames() {
        withRealEnvVars(Map.<String, Object>of(
            "APP_SLACK_TOKEN_ENCRYPTION_CURRENT", "1",
            "APP_SLACK_TOKEN_ENCRYPTION_KEYS_1", "aetherops-slack-token-enc-key-01"))
            .withUserConfiguration(SlackTokenEncryptionProperties.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                SlackTokenEncryptionProperties props = context.getBean(SlackTokenEncryptionProperties.class);
                assertThat(props.getCurrent()).isEqualTo(1);
                assertThat(props.getKeys()).containsEntry(1, "aetherops-slack-token-enc-key-01");
            });
    }

    @Test
    void calendarTokenEncryptionKeysBindFromDocumentedEnvVarNames() {
        withRealEnvVars(Map.<String, Object>of(
            "APP_CALENDAR_TOKEN_ENCRYPTION_CURRENT", "1",
            "APP_CALENDAR_TOKEN_ENCRYPTION_KEYS_1", "aetherops-calendar-token-key-012"))
            .withUserConfiguration(CalendarTokenEncryptionProperties.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                CalendarTokenEncryptionProperties props = context.getBean(CalendarTokenEncryptionProperties.class);
                assertThat(props.getCurrent()).isEqualTo(1);
                assertThat(props.getKeys()).containsEntry(1, "aetherops-calendar-token-key-012");
            });
    }

    @Test
    void integrationsTokenEncryptionKeysBindFromDocumentedEnvVarNames() {
        withRealEnvVars(Map.<String, Object>of(
            "APP_INTEGRATIONS_TOKEN_ENCRYPTION_CURRENT", "1",
            "APP_INTEGRATIONS_TOKEN_ENCRYPTION_KEYS_1", "aetherops-integr-token-enc-key01"))
            .withUserConfiguration(TicketIntegrationTokenEncryptionProperties.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                TicketIntegrationTokenEncryptionProperties props =
                    context.getBean(TicketIntegrationTokenEncryptionProperties.class);
                assertThat(props.getCurrent()).isEqualTo(1);
                assertThat(props.getKeys()).containsEntry(1, "aetherops-integr-token-enc-key01");
            });
    }

    @Test
    void totpEncryptionKeysBindFromDocumentedEnvVarNames() {
        // TOTP's prefix (app.totp.encryption) never had an internal hyphen, so this one was
        // never broken -- included for parity/coverage across all four versioned-key classes.
        withRealEnvVars(Map.<String, Object>of(
            "APP_TOTP_ENCRYPTION_CURRENT", "1",
            "APP_TOTP_ENCRYPTION_KEYS_1", "aetherops-totp-encryption-key-01"))
            .withUserConfiguration(TotpEncryptionProperties.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                TotpEncryptionProperties props = context.getBean(TotpEncryptionProperties.class);
                assertThat(props.getCurrent()).isEqualTo(1);
                assertThat(props.getKeys()).containsEntry(1, "aetherops-totp-encryption-key-01");
            });
    }
}
