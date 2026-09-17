package com.aetherops.slack;

import com.aetherops.oncall.OnCallRotation;
import com.aetherops.oncall.OnCallRotationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SlackCommandControllerTest {

    private static final String TEAM_ID = "T12345";
    private static final String RESPONSE_URL = "https://hooks.slack.com/commands/T12345/999/abc";
    private static final String DASHBOARD_BASE_URL = "http://localhost:8080";
    private final UUID tenantId = UUID.randomUUID();

    private SlackWorkspaceRepository workspaceRepository;
    private OnCallRotationRepository rotationRepository;
    private SlackCommandController controller;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(SlackWorkspaceRepository.class);
        rotationRepository = mock(OnCallRotationRepository.class);
        controller = new SlackCommandController(workspaceRepository, rotationRepository, DASHBOARD_BASE_URL);

        SlackWorkspace workspace = SlackWorkspace.builder().tenantId(tenantId).teamId(TEAM_ID).build();
        when(workspaceRepository.findByTeamId(TEAM_ID)).thenReturn(Optional.of(workspace));
    }

    private OnCallRotation rotationWithParticipant(String phoneNumber) {
        return OnCallRotation.builder()
            .id(1L)
            .tenantId(tenantId)
            .serviceId("payment-svc")
            .rotationStartAt(Instant.now())
            .shiftLengthHours(24)
            .participants(List.of(phoneNumber))
            .build();
    }

    @Test
    void handle_unknownWorkspace_returnsNotConnectedMessage() {
        when(workspaceRepository.findByTeamId("T-UNKNOWN")).thenReturn(Optional.empty());

        Map<String, String> response = controller.handle("T-UNKNOWN", "/oncall", "payment-svc", RESPONSE_URL, "U1", "alice");

        assertThat(response.get("text")).contains("isn't connected");
        verifyNoInteractions(rotationRepository);
    }

    @Test
    void handle_blankText_returnsUsageMessage() {
        Map<String, String> response = controller.handle(TEAM_ID, "/oncall", "", RESPONSE_URL, "U1", "alice");
        assertThat(response.get("text")).contains("Usage:");
    }

    @Test
    void oncall_knownRotation_masksPhoneNumber() {
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "payment-svc"))
            .thenReturn(rotationWithParticipant("+15551234567"));

        Map<String, String> response = controller.handle(TEAM_ID, "/oncall", "payment-svc", RESPONSE_URL, "U1", "alice");

        assertThat(response.get("text")).contains("4567");
        assertThat(response.get("text")).doesNotContain("+15551234567");
        assertThat(response.get("response_type")).isEqualTo("ephemeral");
    }

    @Test
    void oncall_noRotationFound_returnsNotFoundMessage() {
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "payment-svc")).thenReturn(null);

        Map<String, String> response = controller.handle(TEAM_ID, "/oncall", "payment-svc", RESPONSE_URL, "U1", "alice");

        assertThat(response.get("text")).contains("No on-call rotation found");
    }

    @Test
    void oncall_rotationWithNoParticipants_returnsNoEngineerMessage() {
        OnCallRotation rotation = OnCallRotation.builder()
            .id(1L).tenantId(tenantId).serviceId("payment-svc")
            .rotationStartAt(Instant.now()).shiftLengthHours(24)
            .participants(List.of())
            .build();
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "payment-svc")).thenReturn(rotation);

        Map<String, String> response = controller.handle(TEAM_ID, "/oncall", "payment-svc", RESPONSE_URL, "U1", "alice");

        assertThat(response.get("text")).contains("No on-call engineer currently scheduled");
    }

    // Step H1 Part 2 (see STEP_REPORT.md): /page no longer triggers paging at all -- there is no
    // Slack-user-to-AetherOps-user mapping to authorize it, and a half-gated command (a feature
    // flag a tenant has to remember to flip) is worse than an absent one. It now always responds
    // with a redirect to the dashboard and never sends SMS, regardless of workspace or text.
    @Test
    void page_neverPages_respondsWithDashboardRedirect() {
        Map<String, String> response = controller.handle(TEAM_ID, "/page", "payment-svc database is on fire", RESPONSE_URL, "U0G9QF9C6", "alice");

        assertThat(response.get("text")).contains("removed").contains(DASHBOARD_BASE_URL);
        assertThat(response.get("response_type")).isEqualTo("ephemeral");
    }

    @Test
    void page_withNoMessageText_stillRespondsWithDashboardRedirect_neverPages() {
        Map<String, String> response = controller.handle(TEAM_ID, "/page", "payment-svc", RESPONSE_URL, "U0G9QF9C6", "alice");

        assertThat(response.get("text")).contains("removed").contains(DASHBOARD_BASE_URL);
    }

    @Test
    void unrecognizedCommand_returnsErrorMessage() {
        Map<String, String> response = controller.handle(TEAM_ID, "/unknown", "payment-svc", RESPONSE_URL, "U1", "alice");

        assertThat(response.get("text")).contains("Unrecognized command");
        verifyNoInteractions(rotationRepository);
    }
}
