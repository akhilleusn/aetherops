package com.aetherops;

import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.service.JwtService;
import com.aetherops.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;
import java.util.function.Supplier;

@SpringBootTest
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("aetherops_test")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("create-app-role.sql");

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

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TestTransactionHelper testTransactionHelper;

    /**
     * Runs a raw repository call (or any code) under a real, RLS-honest tenant context.
     * A bare repository call from test code (e.g. {@code incidentRepository.findAll()})
     * does NOT reliably propagate app.tenant_id to Postgres on its own — Spring Data's own
     * per-repository transactional advisor is a different object from the one
     * TenantContextInterceptor's aspect reliably wins the ordering tie against for
     * user-defined @Transactional service methods — so under enforced RLS it can silently
     * see zero rows even after TenantContext.set(tenantId). Wrapping the call in a real
     * @Transactional bean method (TestTransactionHelper) forces the same reliable path.
     */
    protected <T> T withTenant(UUID tenantId, Supplier<T> action) {
        TenantContext.set(tenantId);
        return testTransactionHelper.runInTransaction(action);
    }

    /**
     * The "test" profile's security chain (TestSecurityConfig) is permit-all, but
     * TenantContextFilter and IpRateLimitFilter are plain {@code @Component} filters that
     * Spring Boot auto-registers globally regardless of which SecurityFilterChain is
     * active — so TenantContextFilter still runs on every request and clears
     * TenantContext in its {@code finally} block once that request completes. A
     * ThreadLocal set once in a test body does NOT survive past the next mockMvc call.
     * Attach a real JWT via this instead — it's parsed fresh on every single request,
     * exactly like production, so it survives any number of sequential calls.
     */
    protected RequestPostProcessor asTenant(UUID tenantId) {
        return asTenant(tenantId, UUID.randomUUID(), Role.OWNER);
    }

    protected RequestPostProcessor asTenant(UUID tenantId, UUID userId, Role role) {
        User user = new User();
        user.setId(userId);
        user.setTenantId(tenantId);
        user.setEmail("test-" + userId + "@example.com");
        user.setRole(role);
        String token = jwtService.generateToken(user);
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

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
        // App runtime connects as a non-superuser role so RLS (which superusers/BYPASSRLS
        // roles silently ignore even with FORCE ROW LEVEL SECURITY) is actually enforced
        // in tests. Migrations still run as the superuser "test" role, separately below.
        registry.add("spring.datasource.username", () -> "app_user");
        registry.add("spring.datasource.password", () -> "apppass");
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        // application.yml defaults this to true for local/dev convenience. DemoDataInitializer
        // seeds a demo tenant + incidents directly via repositories with no TenantContext set.
        // Demo seeding has no place in an automated test run, and this (unlike
        // application-test.yml) also reaches tests like SecurityEnforcementTest that
        // deliberately run under the default, non-"test" profile.
        registry.add("demo.mode", () -> "false");
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
