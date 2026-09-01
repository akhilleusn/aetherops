package com.aetherops.slack;

import com.aetherops.oncall.OnCallRotation;
import com.aetherops.oncall.OnCallRotationRepository;
import com.aetherops.oncall.OnCallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SlackCommandControllerTest {

    private static final String TEAM_ID = "T12345";
    private final UUID tenantId = UUID.randomUUID();

    private SlackWorkspaceRepository workspaceRepository;
    private OnCallRotationRepository rotationRepository;
    private OnCallService onCallService;
    private SlackCommandController controller;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(SlackWorkspaceRepository.class);
        rotationRepository = mock(OnCallRotationRepository.class);
        onCallService = mock(OnCallService.class);
        controller = new SlackCommandController(workspaceRepository, rotationRepository, onCallService);

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

        Map<String, String> response = controller.handle("T-UNKNOWN", "/oncall", "payment-svc");

        assertThat(response.get("text")).contains("isn't connected");
        verifyNoInteractions(rotationRepository, onCallService);
    }

    @Test
    void handle_blankText_returnsUsageMessage() {
        Map<String, String> response = controller.handle(TEAM_ID, "/oncall", "");
        assertThat(response.get("text")).contains("Usage:");
    }

    @Test
    void oncall_knownRotation_masksPhoneNumber() {
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "payment-svc"))
            .thenReturn(rotationWithParticipant("+15551234567"));

        Map<String, String> response = controller.handle(TEAM_ID, "/oncall", "payment-svc");

        assertThat(response.get("text")).contains("4567");
        assertThat(response.get("text")).doesNotContain("+15551234567");
        assertThat(response.get("response_type")).isEqualTo("ephemeral");
    }

    @Test
    void oncall_noRotationFound_returnsNotFoundMessage() {
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "payment-svc")).thenReturn(null);

        Map<String, String> response = controller.handle(TEAM_ID, "/oncall", "payment-svc");

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

        Map<String, String> response = controller.handle(TEAM_ID, "/oncall", "payment-svc");

        assertThat(response.get("text")).contains("No on-call engineer currently scheduled");
    }

    @Test
    void page_delegatesToOnCallServiceWithMessage() {
        Map<String, String> response = controller.handle(TEAM_ID, "/page", "payment-svc database is on fire");

        verify(onCallService).pageOnCall(eq(tenantId), eq("payment-svc"), eq("database is on fire"));
        assertThat(response.get("text")).contains("Paging on-call engineer");
    }

    @Test
    void page_withNoMessage_usesDefaultMessage() {
        controller.handle(TEAM_ID, "/page", "payment-svc");

        verify(onCallService).pageOnCall(eq(tenantId), eq("payment-svc"), eq("Paged from Slack"));
    }

    @Test
    void unrecognizedCommand_returnsErrorMessage() {
        Map<String, String> response = controller.handle(TEAM_ID, "/unknown", "payment-svc");

        assertThat(response.get("text")).contains("Unrecognized command");
        verifyNoInteractions(rotationRepository, onCallService);
    }
}
