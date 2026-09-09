package com.aetherops.slack;

import com.aetherops.incident.model.CreateIncidentRequest;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.UpdateIncidentRequest;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import com.aetherops.AbstractIntegrationTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class SlackAlertIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc          mockMvc;
    @Autowired ObjectMapper     objectMapper;
    @Autowired IncidentRepository  incidentRepository;
    @Autowired LogEventRepository  logEventRepository;

    @MockitoBean SlackNotifier slackNotifier;

    @BeforeEach
    void setUp() {
        logEventRepository.deleteAll();
        incidentRepository.deleteAll();
        Mockito.clearInvocations(slackNotifier);
    }

    // ── auto-grouping via log ingestion ──────────────────────────────────

    @Test
    void fatalLog_createsIncident_notifiesSlack() throws Exception {
        ingestLog(UUID.randomUUID(), "payment-svc", LogLevel.FATAL, "Out of memory");

        verify(slackNotifier).notify(argThat(i ->
            i.getSeverity() == IncidentSeverity.CRITICAL));
    }

    @Test
    void firstErrorLog_mediumSeverity_doesNotNotify() throws Exception {
        ingestLog(UUID.randomUUID(), "payment-svc", LogLevel.ERROR, "Connection refused");

        verify(slackNotifier, never()).notify(any());
    }

    @Test
    void threeErrorLogs_escalatesToHigh_notifiesOnce() throws Exception {
        UUID tenantId = UUID.randomUUID();
        ingestLog(tenantId, "order-svc", LogLevel.ERROR, "DB timeout");
        ingestLog(tenantId, "order-svc", LogLevel.ERROR, "DB timeout");
        ingestLog(tenantId, "order-svc", LogLevel.ERROR, "DB timeout");

        verify(slackNotifier, times(1)).notify(argThat(i ->
            i.getSeverity() == IncidentSeverity.HIGH));
    }

    @Test
    void fourthErrorLog_alreadyHigh_doesNotNotifyAgain() throws Exception {
        UUID tenantId = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            ingestLog(tenantId, "auth-svc", LogLevel.ERROR, "Token expired");
        }
        // escalation to HIGH happens on the 3rd event; 4th keeps it at HIGH — only one notification
        verify(slackNotifier, times(1)).notify(any());
    }

    // ── manual incident creation ──────────────────────────────────────────

    @Test
    void manualCreate_criticalSeverity_notifiesSlack() throws Exception {
        createIncident(IncidentSeverity.CRITICAL);

        verify(slackNotifier).notify(argThat(i ->
            i.getSeverity() == IncidentSeverity.CRITICAL));
    }

    @Test
    void manualCreate_highSeverity_notifiesSlack() throws Exception {
        createIncident(IncidentSeverity.HIGH);

        verify(slackNotifier).notify(argThat(i ->
            i.getSeverity() == IncidentSeverity.HIGH));
    }

    @Test
    void manualCreate_mediumSeverity_doesNotNotify() throws Exception {
        createIncident(IncidentSeverity.MEDIUM);

        verify(slackNotifier, never()).notify(any());
    }

    // ── manual incident update ────────────────────────────────────────────

    @Test
    void manualUpdate_mediumToHigh_notifiesSlack() throws Exception {
        String body = createIncident(IncidentSeverity.MEDIUM);
        JsonNode data = objectMapper.readTree(body).at("/data");
        UUID incidentId = UUID.fromString(data.at("/id").asText());
        UUID tenantId = UUID.fromString(data.at("/tenantId").asText());
        Mockito.clearInvocations(slackNotifier);

        var req = new UpdateIncidentRequest(null, null, null, IncidentSeverity.HIGH, null);
        mockMvc.perform(patch("/api/v1/incidents/{id}", incidentId)
                .param("tenantId", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .with(asTenant(tenantId)))
            .andExpect(status().isOk());

        verify(slackNotifier).notify(argThat(i ->
            i.getSeverity() == IncidentSeverity.HIGH));
    }

    @Test
    void manualUpdate_highToHigh_doesNotNotifyAgain() throws Exception {
        String body = createIncident(IncidentSeverity.HIGH);
        JsonNode data = objectMapper.readTree(body).at("/data");
        UUID incidentId = UUID.fromString(data.at("/id").asText());
        UUID tenantId = UUID.fromString(data.at("/tenantId").asText());
        Mockito.clearInvocations(slackNotifier);

        var req = new UpdateIncidentRequest(null, null, null, IncidentSeverity.HIGH, null);
        mockMvc.perform(patch("/api/v1/incidents/{id}", incidentId)
                .param("tenantId", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .with(asTenant(tenantId)))
            .andExpect(status().isOk());

        verify(slackNotifier, never()).notify(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void ingestLog(UUID tenantId, String serviceId, LogLevel level, String message) throws Exception {
        LogEventRequest req = new LogEventRequest();
        req.setTenantId(tenantId);
        req.setServiceId(serviceId);
        req.setLevel(level);
        req.setMessage(message);
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated());
    }

    private String createIncident(IncidentSeverity severity) throws Exception {
        UUID tenantId = UUID.randomUUID();
        var req = new CreateIncidentRequest(
            tenantId, null, "Test incident title", null, severity, "test-svc", "production", null, null);
        return mockMvc.perform(post("/api/v1/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    }
}
