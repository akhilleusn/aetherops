package com.aetherops.slack;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.oncall.IncidentTimeline;
import com.aetherops.oncall.IncidentTimelineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SlackInteractivityControllerTest {

    private static final String TEAM_ID = "T12345";
    private static final String DASHBOARD_BASE_URL = "http://localhost:8080";
    private final UUID tenantId = UUID.randomUUID();
    private final UUID otherTenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SlackWorkspaceRepository workspaceRepository;
    private IncidentRepository incidentRepository;
    private SlackApiClient slackApiClient;
    private IncidentTimelineService timelineService;
    private SlackInteractivityController controller;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(SlackWorkspaceRepository.class);
        incidentRepository = mock(IncidentRepository.class);
        slackApiClient = mock(SlackApiClient.class);
        timelineService = mock(IncidentTimelineService.class);
        controller = new SlackInteractivityController(
            objectMapper, workspaceRepository, incidentRepository, slackApiClient,
            timelineService, DASHBOARD_BASE_URL);

        SlackWorkspace workspace = SlackWorkspace.builder().tenantId(tenantId).teamId(TEAM_ID).build();
        when(workspaceRepository.findByTeamId(TEAM_ID)).thenReturn(Optional.of(workspace));
    }

    private Incident incidentForTenant(UUID owningTenantId) {
        return Incident.builder()
            .id(incidentId)
            .tenantId(owningTenantId)
            .title("db down")
            .status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.CRITICAL)
            .serviceId("payment-svc")
            .build();
    }

    private String payload(String actionId, String value) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "type", "block_actions",
                "team", Map.of("id", TEAM_ID),
                "user", Map.of("id", "U0G9QF9C6", "username", "alice"),
                "response_url", "https://hooks.slack.com/actions/T123/456/xyz",
                "actions", List.of(Map.of("action_id", actionId, "value", value))
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Step H1 Part 2 (see STEP_REPORT.md): ack no longer mutates incident state -- an
    // unauthorized ack silences escalation exactly like an unauthorized resolve does, so it's
    // now redirect-only, consistent with resolve. It still records the attempt (and the
    // presented Slack identity) to the timeline as an audit trail.
    @Test
    void ackIncident_doesNotChangeIncidentState_recordsAttemptOnTimeline_respondsWithDashboardLink() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incidentForTenant(tenantId)));

        ResponseEntity<Void> response = controller.handle(payload("ack_incident", incidentId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(timelineService).record(eq(tenantId), eq(incidentId),
            eq(IncidentTimeline.TimelineEventType.SLACK_ACTION_REQUESTED), eq(IncidentSeverity.CRITICAL),
            contains("Acknowledge requested via Slack by alice (U0G9QF9C6)"));
        verify(slackApiClient).respondToInteraction(anyString(),
            contains(DASHBOARD_BASE_URL + "/incidents/" + incidentId));
    }

    @Test
    void resolveIncident_doesNotChangeIncidentState_recordsAttemptOnTimeline_respondsWithDashboardLink() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incidentForTenant(tenantId)));

        ResponseEntity<Void> response = controller.handle(payload("resolve_incident", incidentId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(timelineService).record(eq(tenantId), eq(incidentId),
            eq(IncidentTimeline.TimelineEventType.SLACK_ACTION_REQUESTED), eq(IncidentSeverity.CRITICAL),
            contains("Resolve requested via Slack by alice (U0G9QF9C6)"));
        verify(slackApiClient).respondToInteraction(anyString(),
            contains(DASHBOARD_BASE_URL + "/incidents/" + incidentId));
    }

    @Test
    void unknownWorkspace_returnsForbidden_andDoesNotTouchTimeline() {
        when(workspaceRepository.findByTeamId(TEAM_ID)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.handle(payload("ack_incident", incidentId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(timelineService);
    }

    @Test
    void incidentBelongsToDifferentTenant_returnsForbidden_andDoesNotTouchTimeline() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incidentForTenant(otherTenantId)));

        ResponseEntity<Void> response = controller.handle(payload("ack_incident", incidentId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(timelineService);
    }

    @Test
    void incidentNotFound_returnsForbidden() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.handle(payload("ack_incident", incidentId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(timelineService);
    }

    @Test
    void malformedPayload_returnsBadRequest() {
        ResponseEntity<Void> response = controller.handle("{not valid json");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unhandledActionId_stillRespondsOk_butTakesNoAction() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incidentForTenant(tenantId)));

        ResponseEntity<Void> response = controller.handle(payload("some_other_action", incidentId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(timelineService);
        verifyNoInteractions(slackApiClient);
    }
}
