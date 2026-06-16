package com.aetherops.demo;

import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.incident.model.*;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.repository.IncidentSpecification;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.aetherops.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "demo.mode", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DemoDataInitializer implements ApplicationRunner {

    static final String DEMO_TENANT_NAME   = "AetherOps Demo";
    static final String DEMO_USER_EMAIL    = "demo@aetherops.io";
    static final String DEMO_USER_PASSWORD = "AetherOps_Demo_2024!";

    private final TenantRepository      tenantRepository;
    private final TenantService         tenantService;
    private final UserRepository        userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final IncidentRepository    incidentRepository;

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = ensureTenant();
        ensureDemoUser(tenantId);
        ensureIncidents(tenantId);

        log.info("================================================");
        log.info("  AetherOps Demo ready");
        log.info("  Tenant    : {} ({})", DEMO_TENANT_NAME, tenantId);
        log.info("  Login     : {} / {}", DEMO_USER_EMAIL, DEMO_USER_PASSWORD);
        log.info("  Dashboard : /dashboard.html");
        log.info("================================================");
    }

    private UUID ensureTenant() {
        return tenantRepository.findFirstByName(DEMO_TENANT_NAME)
            .map(Tenant::getId)
            .orElseGet(() -> tenantService.create(
                new CreateTenantRequest(DEMO_TENANT_NAME, Plan.STARTER, 0, 30)).getId());
    }

    private void ensureDemoUser(UUID tenantId) {
        userRepository.findByEmail(DEMO_USER_EMAIL).ifPresentOrElse(
            u -> {
                if (!u.isEmailVerified()) {
                    u.setEmailVerified(true);
                    userRepository.save(u);
                }
            },
            () -> {
                userRepository.save(User.builder()
                    .email(DEMO_USER_EMAIL)
                    .passwordHash(passwordEncoder.encode(DEMO_USER_PASSWORD))
                    .role(Role.MEMBER)
                    .tenantId(tenantId)
                    .twoFaEnabled(false)
                    .emailVerified(true)
                    .build());
                log.info("Created demo user: {}", DEMO_USER_EMAIL);
            }
        );
    }

    private void ensureIncidents(UUID tenantId) {
        long existing = incidentRepository.findAll(
            IncidentSpecification.hasTenant(tenantId), PageRequest.of(0, 1)
        ).getTotalElements();
        if (existing > 0) {
            log.info("Demo incidents already seeded ({} found)", existing);
            return;
        }

        Instant now = Instant.now();
        List<Incident> incidents = List.of(

            // ── Critical, actively on fire ────────────────────────────────
            Incident.builder()
                .tenantId(tenantId)
                .title("Database connection pool exhausted — 47 threads blocked on JDBC acquisition")
                .description(
                    "HikariCP pool saturation detected on payment-svc. All 10 connections are held " +
                    "by slow queries (avg 12s). New requests queue until pool-timeout (30s) then fail " +
                    "with ConnectionTimeoutException. Downstream order-svc is also degrading.")
                .status(IncidentStatus.OPEN)
                .severity(IncidentSeverity.CRITICAL)
                .serviceId("payment-svc")
                .environment("production")
                .fingerprint(fp())
                .eventCount(7)
                .firstSeenAt(now.minus(2, ChronoUnit.HOURS))
                .lastSeenAt(now.minus(18, ChronoUnit.MINUTES))
                .build(),

            // ── Critical, very recent ────────────────────────────────────
            Incident.builder()
                .tenantId(tenantId)
                .title("Redis cache stampede after cold eviction — 3,200 req/s cache misses")
                .description(
                    "After a Redis maxmemory eviction cycle, the in-memory hot-set was wiped. " +
                    "All cache-aside reads now fall through to PostgreSQL simultaneously. " +
                    "DB CPU at 94%, query queue depth 1,200. Redis hit rate dropped from 98% to 7%.")
                .status(IncidentStatus.OPEN)
                .severity(IncidentSeverity.CRITICAL)
                .serviceId("order-svc")
                .environment("production")
                .fingerprint(fp())
                .eventCount(9)
                .firstSeenAt(now.minus(44, ChronoUnit.MINUTES))
                .lastSeenAt(now.minus(8, ChronoUnit.MINUTES))
                .build(),

            // ── High, being investigated ──────────────────────────────────
            Incident.builder()
                .tenantId(tenantId)
                .title("JWT validation failures spike — 1,847 401s in 5 minutes")
                .description(
                    "Sudden spike in 401 Unauthorized responses from auth-svc. " +
                    "Pattern matches tokens issued before a recent secret rotation. " +
                    "Affected users cannot refresh without re-login. On-call engaged.")
                .status(IncidentStatus.INVESTIGATING)
                .severity(IncidentSeverity.HIGH)
                .serviceId("auth-svc")
                .environment("production")
                .fingerprint(fp())
                .eventCount(12)
                .firstSeenAt(now.minus(4, ChronoUnit.HOURS))
                .lastSeenAt(now.minus(3, ChronoUnit.HOURS))
                .build(),

            // ── High, very recent ─────────────────────────────────────────
            Incident.builder()
                .tenantId(tenantId)
                .title("Circuit breaker OPEN for inventory-svc — P99 latency 12.4s")
                .description(
                    "Resilience4j circuit breaker transitioned to OPEN after 6/10 calls " +
                    "exceeded the 10s timeout threshold. API gateway is serving fallback responses " +
                    "with stale inventory data. Downstream checkout flow impacted.")
                .status(IncidentStatus.OPEN)
                .severity(IncidentSeverity.HIGH)
                .serviceId("api-gateway")
                .environment("production")
                .fingerprint(fp())
                .eventCount(3)
                .firstSeenAt(now.minus(22, ChronoUnit.MINUTES))
                .lastSeenAt(now.minus(4, ChronoUnit.MINUTES))
                .build(),

            // ── Medium, ongoing ───────────────────────────────────────────
            Incident.builder()
                .tenantId(tenantId)
                .title("Email delivery queue backing up — P99 latency 8.4s")
                .description(
                    "Notification service email queue depth trending upward since 06:00 UTC. " +
                    "SMTP relay is accepting connections but ACK latency has degraded 10x. " +
                    "Password reset and alert emails are delayed, not lost.")
                .status(IncidentStatus.OPEN)
                .severity(IncidentSeverity.MEDIUM)
                .serviceId("notification-svc")
                .environment("production")
                .fingerprint(fp())
                .eventCount(5)
                .firstSeenAt(now.minus(6, ChronoUnit.HOURS))
                .lastSeenAt(now.minus(1, ChronoUnit.HOURS))
                .build(),

            // ── Medium, resolved yesterday ────────────────────────────────
            Incident.builder()
                .tenantId(tenantId)
                .title("Slow query on tenant isolation filter — missing composite index")
                .description(
                    "Full table scan detected on `incidents` for tenant-scoped queries. " +
                    "Missing composite index on (tenant_id, last_seen_at). Added via Flyway migration V42. " +
                    "Query time dropped from 840ms to 3ms after deployment.")
                .status(IncidentStatus.RESOLVED)
                .severity(IncidentSeverity.MEDIUM)
                .serviceId("user-svc")
                .environment("production")
                .fingerprint(fp())
                .eventCount(4)
                .firstSeenAt(now.minus(2, ChronoUnit.DAYS))
                .lastSeenAt(now.minus(27, ChronoUnit.HOURS))
                .resolvedAt(now.minus(26, ChronoUnit.HOURS))
                .build(),

            // ── Low, resolved ─────────────────────────────────────────────
            Incident.builder()
                .tenantId(tenantId)
                .title("Audit log flush delay during write burst — 340ms lag detected")
                .description(
                    "During peak traffic (14:00–14:20 UTC), audit log async flush fell 340ms behind " +
                    "real-time. No events were lost (write-ahead buffer held them). " +
                    "Resolved by increasing flush thread pool from 2 to 4.")
                .status(IncidentStatus.RESOLVED)
                .severity(IncidentSeverity.LOW)
                .serviceId("audit-svc")
                .environment("production")
                .fingerprint(fp())
                .eventCount(2)
                .firstSeenAt(now.minus(3, ChronoUnit.DAYS))
                .lastSeenAt(now.minus(3, ChronoUnit.DAYS).plus(20, ChronoUnit.MINUTES))
                .resolvedAt(now.minus(3, ChronoUnit.DAYS).plus(35, ChronoUnit.MINUTES))
                .build()
        );

        incidentRepository.saveAll(incidents);
        log.info("Seeded {} demo incidents for tenant {}", incidents.size(), tenantId);
    }

    private static String fp() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
