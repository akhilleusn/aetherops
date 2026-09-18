package com.aetherops.slack;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms Spring Boot's relaxed binding actually produces the {@code Map<Integer, String>}
 * shape {@link SlackTokenEncryptor} relies on -- this is the one part of the versioning change
 * that isn't exercised by a plain {@code new SlackTokenEncryptor(...)} unit test, since that
 * bypasses property binding entirely. The other three purpose-specific properties classes
 * ({@code CalendarTokenEncryptionProperties}, {@code TicketIntegrationTokenEncryptionProperties},
 * {@code TotpEncryptionProperties}) bind identically; this is exercised once as representative
 * rather than four times over.
 */
class SlackTokenEncryptionPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(SlackTokenEncryptionProperties.class);

    @Test
    void bindsCurrentAndIndexedKeysFromEnvVarStyleProperties() {
        runner
            .withPropertyValues(
                "app.slack.token.encryption.current=2",
                "app.slack.token.encryption.keys.0=legacy-key-0000000000000000000000",
                "app.slack.token.encryption.keys.1=old-current-key-000000000000000",
                "app.slack.token.encryption.keys.2=new-current-key-000000000000000")
            .run(context -> {
                SlackTokenEncryptionProperties properties = context.getBean(SlackTokenEncryptionProperties.class);
                assertThat(properties.getCurrent()).isEqualTo(2);
                assertThat(properties.getKeys())
                    .containsEntry(0, "legacy-key-0000000000000000000000")
                    .containsEntry(1, "old-current-key-000000000000000")
                    .containsEntry(2, "new-current-key-000000000000000");
            });
    }

    @Test
    void defaultsCurrentToOneAndKeysToEmptyWhenUnconfigured() {
        runner.run(context -> {
            SlackTokenEncryptionProperties properties = context.getBean(SlackTokenEncryptionProperties.class);
            assertThat(properties.getCurrent()).isEqualTo(1);
            assertThat(properties.getKeys()).isEmpty();
        });
    }
}
