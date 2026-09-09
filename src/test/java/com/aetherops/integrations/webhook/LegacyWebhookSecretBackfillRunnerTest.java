package com.aetherops.integrations.webhook;

import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The behavior under test here is entirely about NEVER leaking a generated secret into the
 * logger, and refusing to generate secrets at all when there's no configured way to retrieve
 * them afterward — see LegacyWebhookSecretBackfillRunner's javadoc.
 */
@ExtendWith(MockitoExtension.class)
class LegacyWebhookSecretBackfillRunnerTest {

    @Mock TenantRepository tenantRepository;
    @Mock WebhookSigningSecretService signingSecretService;
    @Mock ApplicationArguments args;

    LegacyWebhookSecretBackfillRunner runner;

    @BeforeEach
    void setUp() {
        runner = new LegacyWebhookSecretBackfillRunner(tenantRepository, signingSecretService);
        ReflectionTestUtils.setField(runner, "gracePeriodDays", 30L);
    }

    @Test
    void run_refusesAndTouchesNothing_whenNeitherOutputPathNorPrintSecretsFlagGiven() {
        ReflectionTestUtils.setField(runner, "secretsOutputPath", "");
        when(args.containsOption("print-secrets")).thenReturn(false);

        runner.run(args);

        verifyNoInteractions(tenantRepository);
        verifyNoInteractions(signingSecretService);
    }

    @Test
    void run_writesGeneratedSecretsToFile_withOwnerOnlyPermissions_whenOutputPathConfigured(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("nested/webhook-secrets.csv");
        ReflectionTestUtils.setField(runner, "secretsOutputPath", outputFile.toString());
        lenient().when(args.containsOption("print-secrets")).thenReturn(false);

        UUID tenantId = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(tenantId).build();
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(signingSecretService.backfillIfAbsent(eq(tenantId), any(), any(), any(Instant.class)))
            .thenReturn(true);

        runner.run(args);

        assertThat(Files.exists(outputFile)).isTrue();
        String content = Files.readString(outputFile);
        assertThat(content).contains(tenantId.toString());
        assertThat(content).contains("DATADOG").contains("NEW_RELIC").contains("OPSGENIE")
            .contains("ROLLBAR").contains("ALERTMANAGER");
        // 5 static-token providers backfilled for the one tenant.
        verify(signingSecretService, times(5)).backfillIfAbsent(eq(tenantId), any(), any(), any(Instant.class));

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(outputFile);
            assertThat(perms).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void run_doesNotOverwriteOrDuplicate_whenSecretAlreadyBackfilledForAllProviders(@TempDir Path tempDir) {
        Path outputFile = tempDir.resolve("webhook-secrets.csv");
        ReflectionTestUtils.setField(runner, "secretsOutputPath", outputFile.toString());
        lenient().when(args.containsOption("print-secrets")).thenReturn(false);

        UUID tenantId = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(tenantId).build();
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(signingSecretService.backfillIfAbsent(eq(tenantId), any(), any(), any(Instant.class)))
            .thenReturn(false); // every provider already has an operator-configured secret

        runner.run(args);

        assertThat(Files.exists(outputFile)).isFalse(); // nothing generated -> no file, per run()
    }

    @Test
    void run_printsToStdoutOnly_andNeverThroughTheLogger_whenPrintSecretsFlagGiven() {
        ReflectionTestUtils.setField(runner, "secretsOutputPath", "");
        when(args.containsOption("print-secrets")).thenReturn(true);

        UUID tenantId = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(tenantId).build();
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(signingSecretService.backfillIfAbsent(eq(tenantId), any(), any(), any(Instant.class)))
            .thenReturn(true);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            runner.run(args);
        } finally {
            System.setOut(originalOut);
        }

        assertThat(captured.toString()).contains(tenantId.toString()).contains("DATADOG");
    }
}
