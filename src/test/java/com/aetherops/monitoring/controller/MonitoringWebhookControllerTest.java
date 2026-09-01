package com.aetherops.monitoring.controller;

import com.aetherops.apikey.model.ApiKey;
import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.monitoring.model.AlertmanagerWebhookRequest;
import com.aetherops.monitoring.model.DatadogWebhookRequest;
import com.aetherops.monitoring.service.MonitoringAlertIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitoringWebhookControllerTest {

    @Mock private ApiKeyService apiKeyService;
    @Mock private MonitoringAlertIngestionService ingestionService;

    @InjectMocks
    private MonitoringWebhookController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ApiKey apiKey = ApiKey.builder().tenantId(tenantId).projectId(projectId).active(true).build();
        lenient().when(apiKeyService.resolve("raw-key")).thenReturn(apiKey);
    }

    private DatadogWebhookRequest datadogRequest(String alertType, String priority, String transition) {
        DatadogWebhookRequest r = new DatadogWebhookRequest();
        r.setServiceId("payment-svc");
        r.setTitle("High error rate");
        r.setText("Errors spiking");
        r.setAlertType(alertType);
        r.setPriority(priority);
        r.setAlertTransition(transition);
        r.setEnvironment("production");
        return r;
    }

    @Test
    void datadog_errorAlertType_mapsToHighSeverity() {
        ResponseEntity<Void> response = controller.datadog(datadogRequest("error", null, "Triggered"), "raw-key");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ArgumentCaptor<MonitoringAlertIngestionService.AlertSignal> captor =
            ArgumentCaptor.forClass(MonitoringAlertIngestionService.AlertSignal.class);
        verify(ingestionService).ingest(captor.capture());
        assertThat(captor.getValue().severity()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(captor.getValue().firing()).isTrue();
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void datadog_priorityOverridesAlertType() {
        controller.datadog(datadogRequest("warning", "P1", "Triggered"), "raw-key");

        ArgumentCaptor<MonitoringAlertIngestionService.AlertSignal> captor =
            ArgumentCaptor.forClass(MonitoringAlertIngestionService.AlertSignal.class);
        verify(ingestionService).ingest(captor.capture());
        assertThat(captor.getValue().severity()).isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    void datadog_recoveredTransition_marksAsNotFiring() {
        controller.datadog(datadogRequest("error", null, "Recovered"), "raw-key");

        ArgumentCaptor<MonitoringAlertIngestionService.AlertSignal> captor =
            ArgumentCaptor.forClass(MonitoringAlertIngestionService.AlertSignal.class);
        verify(ingestionService).ingest(captor.capture());
        assertThat(captor.getValue().firing()).isFalse();
    }

    @Test
    void datadog_unknownAlertType_defaultsToLow() {
        controller.datadog(datadogRequest("success", null, "Triggered"), "raw-key");

        ArgumentCaptor<MonitoringAlertIngestionService.AlertSignal> captor =
            ArgumentCaptor.forClass(MonitoringAlertIngestionService.AlertSignal.class);
        verify(ingestionService).ingest(captor.capture());
        assertThat(captor.getValue().severity()).isEqualTo(IncidentSeverity.LOW);
    }

    private AlertmanagerWebhookRequest.Alert alertmanagerAlert(String status, String severity, String alertname) {
        AlertmanagerWebhookRequest.Alert alert = new AlertmanagerWebhookRequest.Alert();
        alert.setStatus(status);
        alert.setLabels(severity != null
            ? Map.of("alertname", alertname, "service", "api-svc", "severity", severity)
            : Map.of("alertname", alertname, "service", "api-svc"));
        alert.setAnnotations(Map.of("summary", "summary text", "description", "desc text"));
        alert.setStartsAt(Instant.now());
        return alert;
    }

    @Test
    void alertmanager_criticalSeverityLabel_mapsToCritical() {
        AlertmanagerWebhookRequest request = new AlertmanagerWebhookRequest();
        request.setAlerts(List.of(alertmanagerAlert("firing", "critical", "HighLatency")));

        controller.prometheusAlertmanager(request, "raw-key");

        ArgumentCaptor<MonitoringAlertIngestionService.AlertSignal> captor =
            ArgumentCaptor.forClass(MonitoringAlertIngestionService.AlertSignal.class);
        verify(ingestionService).ingest(captor.capture());
        assertThat(captor.getValue().severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(captor.getValue().serviceId()).isEqualTo("api-svc");
        assertThat(captor.getValue().alertName()).isEqualTo("HighLatency");
    }

    @Test
    void alertmanager_resolvedStatus_marksAsNotFiring() {
        AlertmanagerWebhookRequest request = new AlertmanagerWebhookRequest();
        request.setAlerts(List.of(alertmanagerAlert("resolved", "critical", "HighLatency")));

        controller.prometheusAlertmanager(request, "raw-key");

        ArgumentCaptor<MonitoringAlertIngestionService.AlertSignal> captor =
            ArgumentCaptor.forClass(MonitoringAlertIngestionService.AlertSignal.class);
        verify(ingestionService).ingest(captor.capture());
        assertThat(captor.getValue().firing()).isFalse();
    }

    @Test
    void alertmanager_missingSeverityLabel_defaultsToMedium() {
        AlertmanagerWebhookRequest request = new AlertmanagerWebhookRequest();
        request.setAlerts(List.of(alertmanagerAlert("firing", null, "SomeAlert")));

        controller.prometheusAlertmanager(request, "raw-key");

        ArgumentCaptor<MonitoringAlertIngestionService.AlertSignal> captor =
            ArgumentCaptor.forClass(MonitoringAlertIngestionService.AlertSignal.class);
        verify(ingestionService).ingest(captor.capture());
        assertThat(captor.getValue().severity()).isEqualTo(IncidentSeverity.MEDIUM);
    }

    @Test
    void alertmanager_multipleAlerts_ingestsEachOne() {
        AlertmanagerWebhookRequest request = new AlertmanagerWebhookRequest();
        request.setAlerts(List.of(
            alertmanagerAlert("firing", "warning", "Alert1"),
            alertmanagerAlert("firing", "critical", "Alert2")));

        controller.prometheusAlertmanager(request, "raw-key");

        verify(ingestionService, times(2)).ingest(any());
    }

    @Test
    void alertmanager_emptyAlertsList_ingestsNothing() {
        AlertmanagerWebhookRequest request = new AlertmanagerWebhookRequest();
        request.setAlerts(List.of());

        controller.prometheusAlertmanager(request, "raw-key");

        verifyNoInteractions(ingestionService);
    }
}
