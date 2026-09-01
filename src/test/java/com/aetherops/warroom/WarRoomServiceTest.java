package com.aetherops.warroom;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WarRoomServiceTest {

    @Mock private WarRoomRepository warRoomRepository;
    @Mock private WarRoomParticipantRepository participantRepository;
    @Mock private IncidentRepository incidentRepository;

    @InjectMocks
    private WarRoomService warRoomService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();
    private final UUID warRoomId = UUID.randomUUID();

    private Incident incident(String slackChannelId, String slackThreadTs) {
        return Incident.builder()
            .id(incidentId).tenantId(tenantId).title("DB down")
            .status(IncidentStatus.OPEN).severity(IncidentSeverity.CRITICAL)
            .serviceId("db-svc").slackChannelId(slackChannelId).slackThreadTs(slackThreadTs)
            .build();
    }

    private WarRoom activeWarRoom() {
        return WarRoom.builder()
            .id(warRoomId).tenantId(tenantId).incidentId(incidentId)
            .status(WarRoomStatus.ACTIVE).openedAt(Instant.now()).openedBy("AUTO")
            .build();
    }

    @Test
    void open_newIncident_snapshotsSlackFieldsFromIncident() {
        when(warRoomRepository.findByIncidentIdAndStatus(incidentId, WarRoomStatus.ACTIVE)).thenReturn(Optional.empty());
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident("C123", "1700.001")));
        when(warRoomRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(participantRepository.findByWarRoomIdAndLeftAtIsNull(any())).thenReturn(List.of());

        WarRoomResponse response = warRoomService.open(tenantId, incidentId, "AUTO");

        assertThat(response.getSlackChannelId()).isEqualTo("C123");
        assertThat(response.getSlackThreadTs()).isEqualTo("1700.001");
        assertThat(response.getStatus()).isEqualTo(WarRoomStatus.ACTIVE);
        assertThat(response.getOpenedBy()).isEqualTo("AUTO");
    }

    @Test
    void open_incidentWithNoSlackThread_slackFieldsNull() {
        when(warRoomRepository.findByIncidentIdAndStatus(incidentId, WarRoomStatus.ACTIVE)).thenReturn(Optional.empty());
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident(null, null)));
        when(warRoomRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(participantRepository.findByWarRoomIdAndLeftAtIsNull(any())).thenReturn(List.of());

        WarRoomResponse response = warRoomService.open(tenantId, incidentId, "MANUAL");

        assertThat(response.getSlackChannelId()).isNull();
        assertThat(response.getSlackThreadTs()).isNull();
    }

    @Test
    void open_alreadyActiveWarRoomExists_returnsExistingWithoutCreatingDuplicate() {
        when(warRoomRepository.findByIncidentIdAndStatus(incidentId, WarRoomStatus.ACTIVE)).thenReturn(Optional.of(activeWarRoom()));
        when(participantRepository.findByWarRoomIdAndLeftAtIsNull(warRoomId)).thenReturn(List.of());

        WarRoomResponse response = warRoomService.open(tenantId, incidentId, "MANUAL");

        assertThat(response.getId()).isEqualTo(warRoomId);
        verify(warRoomRepository, never()).save(any());
        verify(incidentRepository, never()).findById(any());
    }

    @Test
    void open_incidentNotFound_throwsResourceNotFoundException() {
        when(warRoomRepository.findByIncidentIdAndStatus(incidentId, WarRoomStatus.ACTIVE)).thenReturn(Optional.empty());
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> warRoomService.open(tenantId, incidentId, "MANUAL"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void open_concurrentInsertRace_fallsBackToFetchingWinner() {
        WarRoom winner = activeWarRoom();
        when(warRoomRepository.findByIncidentIdAndStatus(incidentId, WarRoomStatus.ACTIVE))
            .thenReturn(Optional.empty(), Optional.of(winner));
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident("C1", "1.1")));
        when(warRoomRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(participantRepository.findByWarRoomIdAndLeftAtIsNull(warRoomId)).thenReturn(List.of());

        WarRoomResponse response = warRoomService.open(tenantId, incidentId, "AUTO");

        assertThat(response.getId()).isEqualTo(warRoomId);
    }

    @Test
    void join_newParticipant_addsThem() {
        WarRoom warRoom = activeWarRoom();
        UUID userId = UUID.randomUUID();
        when(warRoomRepository.findById(warRoomId)).thenReturn(Optional.of(warRoom));
        when(participantRepository.findByWarRoomIdAndUserIdAndLeftAtIsNull(warRoomId, userId)).thenReturn(Optional.empty());
        when(participantRepository.findByWarRoomIdAndLeftAtIsNull(warRoomId)).thenReturn(List.of(
            WarRoomParticipant.builder().warRoomId(warRoomId).userId(userId).role(ParticipantRole.RESPONDER).joinedAt(Instant.now()).build()));

        WarRoomResponse response = warRoomService.join(tenantId, warRoomId, userId, ParticipantRole.RESPONDER);

        ArgumentCaptor<WarRoomParticipant> captor = ArgumentCaptor.forClass(WarRoomParticipant.class);
        verify(participantRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(response.getParticipants()).hasSize(1);
    }

    @Test
    void join_alreadyActiveParticipant_doesNotDuplicate() {
        WarRoom warRoom = activeWarRoom();
        UUID userId = UUID.randomUUID();
        when(warRoomRepository.findById(warRoomId)).thenReturn(Optional.of(warRoom));
        when(participantRepository.findByWarRoomIdAndUserIdAndLeftAtIsNull(warRoomId, userId))
            .thenReturn(Optional.of(WarRoomParticipant.builder().warRoomId(warRoomId).userId(userId).role(ParticipantRole.RESPONDER).build()));
        when(participantRepository.findByWarRoomIdAndLeftAtIsNull(warRoomId)).thenReturn(List.of());

        warRoomService.join(tenantId, warRoomId, userId, ParticipantRole.RESPONDER);

        verify(participantRepository, never()).save(any());
    }

    @Test
    void join_warRoomNotFound_throwsResourceNotFoundException() {
        when(warRoomRepository.findById(warRoomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> warRoomService.join(tenantId, warRoomId, UUID.randomUUID(), ParticipantRole.RESPONDER))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void join_warRoomBelongsToDifferentTenant_throwsResourceNotFoundException() {
        WarRoom warRoom = activeWarRoom();
        when(warRoomRepository.findById(warRoomId)).thenReturn(Optional.of(warRoom));

        assertThatThrownBy(() -> warRoomService.join(UUID.randomUUID(), warRoomId, UUID.randomUUID(), ParticipantRole.RESPONDER))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void leave_activeParticipant_setsLeftAt() {
        WarRoom warRoom = activeWarRoom();
        UUID userId = UUID.randomUUID();
        WarRoomParticipant participant = WarRoomParticipant.builder().warRoomId(warRoomId).userId(userId).role(ParticipantRole.RESPONDER).build();
        when(warRoomRepository.findById(warRoomId)).thenReturn(Optional.of(warRoom));
        when(participantRepository.findByWarRoomIdAndUserIdAndLeftAtIsNull(warRoomId, userId)).thenReturn(Optional.of(participant));
        when(participantRepository.findByWarRoomIdAndLeftAtIsNull(warRoomId)).thenReturn(List.of());

        warRoomService.leave(tenantId, warRoomId, userId);

        ArgumentCaptor<WarRoomParticipant> captor = ArgumentCaptor.forClass(WarRoomParticipant.class);
        verify(participantRepository).save(captor.capture());
        assertThat(captor.getValue().getLeftAt()).isNotNull();
    }

    @Test
    void leave_nonParticipant_isNoOp() {
        WarRoom warRoom = activeWarRoom();
        UUID userId = UUID.randomUUID();
        when(warRoomRepository.findById(warRoomId)).thenReturn(Optional.of(warRoom));
        when(participantRepository.findByWarRoomIdAndUserIdAndLeftAtIsNull(warRoomId, userId)).thenReturn(Optional.empty());
        when(participantRepository.findByWarRoomIdAndLeftAtIsNull(warRoomId)).thenReturn(List.of());

        warRoomService.leave(tenantId, warRoomId, userId);

        verify(participantRepository, never()).save(any());
    }

    @Test
    void leave_warRoomBelongsToDifferentTenant_throwsResourceNotFoundException() {
        WarRoom warRoom = activeWarRoom();
        when(warRoomRepository.findById(warRoomId)).thenReturn(Optional.of(warRoom));

        assertThatThrownBy(() -> warRoomService.leave(UUID.randomUUID(), warRoomId, UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void close_activeWarRoom_setsClosedStatusAndTimestamp() {
        WarRoom warRoom = activeWarRoom();
        when(warRoomRepository.findById(warRoomId)).thenReturn(Optional.of(warRoom));
        when(warRoomRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(participantRepository.findByWarRoomIdAndLeftAtIsNull(warRoomId)).thenReturn(List.of());

        WarRoomResponse response = warRoomService.close(tenantId, warRoomId);

        assertThat(response.getStatus()).isEqualTo(WarRoomStatus.CLOSED);
        assertThat(response.getClosedAt()).isNotNull();
    }

    @Test
    void close_notActive_throwsResourceNotFoundException() {
        WarRoom closed = activeWarRoom();
        closed.setStatus(WarRoomStatus.CLOSED);
        when(warRoomRepository.findById(warRoomId)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> warRoomService.close(tenantId, warRoomId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void close_wrongTenant_throwsResourceNotFoundException() {
        WarRoom warRoom = activeWarRoom();
        when(warRoomRepository.findById(warRoomId)).thenReturn(Optional.of(warRoom));

        assertThatThrownBy(() -> warRoomService.close(UUID.randomUUID(), warRoomId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void get_notFound_throwsResourceNotFoundException() {
        when(warRoomRepository.findById(warRoomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> warRoomService.get(tenantId, warRoomId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void get_found_returnsWithParticipants() {
        WarRoom warRoom = activeWarRoom();
        UUID userId = UUID.randomUUID();
        when(warRoomRepository.findById(warRoomId)).thenReturn(Optional.of(warRoom));
        when(participantRepository.findByWarRoomIdAndLeftAtIsNull(warRoomId)).thenReturn(List.of(
            WarRoomParticipant.builder().warRoomId(warRoomId).userId(userId).role(ParticipantRole.INCIDENT_COMMANDER).joinedAt(Instant.now()).build()));

        WarRoomResponse response = warRoomService.get(tenantId, warRoomId);

        assertThat(response.getParticipants()).hasSize(1);
        assertThat(response.getParticipants().get(0).getRole()).isEqualTo(ParticipantRole.INCIDENT_COMMANDER);
    }

    @Test
    void list_filtersByTenantAndStatus() {
        when(warRoomRepository.findByTenantIdAndStatus(tenantId, WarRoomStatus.CLOSED)).thenReturn(List.of(activeWarRoom()));
        when(participantRepository.findByWarRoomIdAndLeftAtIsNull(warRoomId)).thenReturn(List.of());

        List<WarRoomResponse> results = warRoomService.list(tenantId, WarRoomStatus.CLOSED);

        assertThat(results).hasSize(1);
        verify(warRoomRepository).findByTenantIdAndStatus(tenantId, WarRoomStatus.CLOSED);
    }

    @Test
    void listActive_delegatesToListWithActiveStatus() {
        when(warRoomRepository.findByTenantIdAndStatus(tenantId, WarRoomStatus.ACTIVE)).thenReturn(List.of());

        warRoomService.listActive(tenantId);

        verify(warRoomRepository).findByTenantIdAndStatus(tenantId, WarRoomStatus.ACTIVE);
    }
}
