package com.aetherops.slack;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SlackNotifierTest {

    private static final String WEBHOOK   = "https://hooks.slack.com/services/test/webhook";
    private static final String DASHBOARD = "http://localhost:8080";

    private RestClient mockRestClient;

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
    }

    private Incident buildIncident(IncidentSeverity severity) {
        return Incident.builder()
            .id(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
            .title("[ERROR] payment-svc: Connection refused to database")
            .severity(severity)
            .serviceId("payment-svc")
            .environment("production")
            .eventCount(5)
            .build();
    }

    // ── blank URL — skip silently ─────────────────────────────────────────

    @Test
    void blankWebhookUrl_notify_doesNotCallRestClient() {
        SlackNotifier notifier = new SlackNotifier("", DASHBOARD, mockRestClient);
        notifier.notify(buildIncident(IncidentSeverity.CRITICAL));
        verify(mockRestClient, never()).post();
    }

    @Test
    void whitespaceWebhookUrl_notify_doesNotCallRestClient() {
        SlackNotifier notifier = new SlackNotifier("   ", DASHBOARD, mockRestClient);
        notifier.notify(buildIncident(IncidentSeverity.HIGH));
        verify(mockRestClient, never()).post();
    }

    // ── payload content ───────────────────────────────────────────────────

    @Test
    void buildPayload_criticalSeverity_containsCritical() {
        SlackNotifier notifier = new SlackNotifier(WEBHOOK, DASHBOARD, mockRestClient);
        String payload = notifier.buildPayload(buildIncident(IncidentSeverity.CRITICAL));
        assertThat(payload).contains("CRITICAL");
    }

    @Test
    void buildPayload_criticalSeverity_containsAlertEmoji() {
        SlackNotifier notifier = new SlackNotifier(WEBHOOK, DASHBOARD, mockRestClient);
        String payload = notifier.buildPayload(buildIncident(IncidentSeverity.CRITICAL));
        assertThat(payload).contains("🚨");
    }

    @Test
    void buildPayload_highSeverity_containsWarningEmoji() {
        SlackNotifier notifier = new SlackNotifier(WEBHOOK, DASHBOARD, mockRestClient);
        String payload = notifier.buildPayload(buildIncident(IncidentSeverity.HIGH));
        assertThat(payload).contains("⚠️");
    }

    @Test
    void buildPayload_containsServiceId() {
        SlackNotifier notifier = new SlackNotifier(WEBHOOK, DASHBOARD, mockRestClient);
        String payload = notifier.buildPayload(buildIncident(IncidentSeverity.CRITICAL));
        assertThat(payload).contains("payment-svc");
    }

    @Test
    void buildPayload_containsTitle() {
        SlackNotifier notifier = new SlackNotifier(WEBHOOK, DASHBOARD, mockRestClient);
        String payload = notifier.buildPayload(buildIncident(IncidentSeverity.CRITICAL));
        assertThat(payload).contains("Connection refused to database");
    }

    @Test
    void buildPayload_containsEventCount() {
        SlackNotifier notifier = new SlackNotifier(WEBHOOK, DASHBOARD, mockRestClient);
        String payload = notifier.buildPayload(buildIncident(IncidentSeverity.HIGH));
        assertThat(payload).contains("5");
    }

    @Test
    void buildPayload_containsIncidentLink() {
        SlackNotifier notifier = new SlackNotifier(WEBHOOK, DASHBOARD, mockRestClient);
        Incident incident = buildIncident(IncidentSeverity.CRITICAL);
        String payload = notifier.buildPayload(incident);
        assertThat(payload).contains("/incidents/" + incident.getId());
    }

    @Test
    void buildPayload_containsEnvironment() {
        SlackNotifier notifier = new SlackNotifier(WEBHOOK, DASHBOARD, mockRestClient);
        String payload = notifier.buildPayload(buildIncident(IncidentSeverity.CRITICAL));
        assertThat(payload).contains("production");
    }
}
