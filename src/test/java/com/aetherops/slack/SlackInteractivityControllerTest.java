package com.aetherops.slack;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.model.UpdateIncidentRequest;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.service.IncidentService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SlackInteractivityControllerTest {

    private static final String TEAM_ID = "T12345";
    private final UUID tenantId = UUID.randomUUID();
    private final UUID otherTenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SlackWorkspaceRepository workspaceRepository;
    private IncidentRepository incidentRepository;
    private IncidentService incidentService;
    private SlackApiClient slackApiClient;
    private SlackInteractivityController controller;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(SlackWorkspaceRepository.class);
        incidentRepository = mock(IncidentRepository.class);
        incidentService = mock(IncidentService.class);
        slackApiClient = mock(SlackApiClient.class);
        controller = new SlackInteractivityController(
            objectMapper, workspaceRepository, incidentRepository, incidentService, slackApiClient);

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
                "user", Map.of("username", "alice"),
                "response_url", "https://hooks.slack.com/actions/T123/456/xyz",
                "actions", List.of(Map.of("action_id", actionId, "value", value))
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void ackIncident_movesIncidentToInvestigating_andRespondsOk() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incidentForTenant(tenantId)));

        ResponseEntity<Void> response = controller.handle(payload("ack_incident", incidentId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(incidentService).update(eq(incidentId),
            eq(new UpdateIncidentRequest(null, null, IncidentStatus.INVESTIGATING, null, null)));
        verify(slackApiClient).respondToInteraction(anyString(), contains("Acknowledged by alice"));
    }

    @Test
    void resolveIncident_resolvesIncident_andRespondsOk() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incidentForTenant(tenantId)));

        ResponseEntity<Void> response = controller.handle(payload("resolve_incident", incidentId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(incidentService).update(eq(incidentId),
            eq(new UpdateIncidentRequest(null, null, IncidentStatus.RESOLVED, null, null)));
        verify(slackApiClient).respondToInteraction(anyString(), contains("Resolved by alice"));
    }

    @Test
    void unknownWorkspace_returnsForbidden_andDoesNotTouchIncident() {
        when(workspaceRepository.findByTeamId(TEAM_ID)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.handle(payload("ack_incident", incidentId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(incidentService);
    }

    @Test
    void incidentBelongsToDifferentTenant_returnsForbidden_andDoesNotTouchIncident() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incidentForTenant(otherTenantId)));

        ResponseEntity<Void> response = controller.handle(payload("ack_incident", incidentId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(incidentService, never()).update(any(), any());
    }

    @Test
    void incidentNotFound_returnsForbidden() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.handle(payload("ack_incident", incidentId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(incidentService, never()).update(any(), any());
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
        verifyNoInteractions(incidentService);
        verifyNoInteractions(slackApiClient);
    }
}
