package com.aetherops;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("aetherops_test")
            .withUsername("test")
            .withPassword("test");

    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE " +
            "totp_backup_codes, refresh_tokens, password_reset_tokens, " +
            "ai_analyses, evidence_items, runbook_suggestions, postmortem_drafts, " +
            "audit_logs, api_keys, usage_records, tenant_monthly_usage, " +
            "idempotency_keys, incident_events, outbox_events, saga_steps, " +
            "webhook_deliveries, webhook_endpoints, " +
            "incident_embeddings, incident_reports, " +
            "users, log_events, incidents, services, projects, " +
            "tenants CASCADE"
        );
        stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("app.jwt.secret", () -> "test-secret-key-minimum-32-characters-long");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.kafka.consumer.auto-startup", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "500");
        registry.add("app.ip-rate-limit.requests-per-window", () -> "1000000");
        registry.add("app.totp.encryption-key", () -> "aetherops-totp-encryption-key-01");
        // Required, no-default secrets (see e.g. SlackTokenEncryptor/CalendarTokenEncryptor/
        // TicketIntegrationTokenEncryptor/CalendarOAuthStateService) — supplied directly here,
        // not via application-test.yml's "test" profile, because SecurityEnforcementTest
        // deliberately runs under the *default* profile (so the real SecurityConfig, not the
        // permit-all TestSecurityConfig, is what it exercises) while still needing these beans
        // to construct.
        registry.add("app.slack.token-encryption-key", () -> "aetherops-slack-token-enc-key-01");
        registry.add("app.calendar.token-encryption-key", () -> "aetherops-calendar-token-key-012");
        registry.add("app.integrations.token-encryption-key", () -> "aetherops-integr-token-enc-key01");
        registry.add("app.calendar.oauth-state-secret", () -> "aetherops-test-calendar-oauth-state-secret");
    }
}
