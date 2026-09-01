package com.aetherops.warroom;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarRoomService {

    private final WarRoomRepository warRoomRepository;
    private final WarRoomParticipantRepository participantRepository;
    private final IncidentRepository incidentRepository;

    /**
     * Idempotent: an incident can have at most one ACTIVE war room (enforced by a partial
     * unique index on incident_id), so a concurrent open (the auto-open sweep racing a manual
     * open) falls back to fetching the winner rather than erroring.
     */
    @Transactional
    public WarRoomResponse open(UUID tenantId, UUID incidentId, String openedBy) {
        var existing = warRoomRepository.findByIncidentIdAndStatus(incidentId, WarRoomStatus.ACTIVE);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        Incident incident = incidentRepository.findById(incidentId)
            .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + incidentId));

        WarRoom warRoom = WarRoom.builder()
            .tenantId(tenantId)
            .incidentId(incidentId)
            .status(WarRoomStatus.ACTIVE)
            .openedAt(Instant.now())
            .openedBy(openedBy)
            .slackChannelId(incident.getSlackChannelId())
            .slackThreadTs(incident.getSlackThreadTs())
            .build();

        try {
            WarRoom saved = warRoomRepository.save(warRoom);
            log.info("Opened war room id={} incidentId={} tenantId={} openedBy={}",
                saved.getId(), incidentId, tenantId, openedBy);
            return toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            log.debug("Concurrent war room open for incidentId={}, re-fetching the winner", incidentId);
            return warRoomRepository.findByIncidentIdAndStatus(incidentId, WarRoomStatus.ACTIVE)
                .map(this::toResponse)
                .orElseThrow(() -> e);
        }
    }

    /** Idempotent: rejoining while already an active participant returns the existing state unchanged. */
    @Transactional
    public WarRoomResponse join(UUID tenantId, UUID warRoomId, UUID userId, ParticipantRole role) {
        WarRoom warRoom = requireByIdAndTenant(warRoomId, tenantId);

        if (participantRepository.findByWarRoomIdAndUserIdAndLeftAtIsNull(warRoomId, userId).isEmpty()) {
            WarRoomParticipant participant = WarRoomParticipant.builder()
                .warRoomId(warRoomId)
                .userId(userId)
                .role(role)
                .joinedAt(Instant.now())
                .build();
            try {
                participantRepository.save(participant);
                log.info("userId={} joined war room id={} role={}", userId, warRoomId, role);
            } catch (DataIntegrityViolationException e) {
                log.debug("Concurrent join for warRoomId={} userId={}, already a participant", warRoomId, userId);
            }
        }

        return toResponse(warRoom);
    }

    /** No-op (not an error) if the user isn't currently an active participant. */
    @Transactional
    public WarRoomResponse leave(UUID tenantId, UUID warRoomId, UUID userId) {
        WarRoom warRoom = requireByIdAndTenant(warRoomId, tenantId);

        participantRepository.findByWarRoomIdAndUserIdAndLeftAtIsNull(warRoomId, userId)
            .ifPresent(participant -> {
                participant.setLeftAt(Instant.now());
                participantRepository.save(participant);
                log.info("userId={} left war room id={}", userId, warRoomId);
            });

        return toResponse(warRoom);
    }

    @Transactional
    public WarRoomResponse close(UUID tenantId, UUID warRoomId) {
        WarRoom warRoom = warRoomRepository.findById(warRoomId)
            .filter(w -> w.getTenantId().equals(tenantId) && w.getStatus() == WarRoomStatus.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("Active war room not found: " + warRoomId));

        warRoom.setStatus(WarRoomStatus.CLOSED);
        warRoom.setClosedAt(Instant.now());
        WarRoom saved = warRoomRepository.save(warRoom);
        log.info("Closed war room id={} tenantId={}", warRoomId, tenantId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public WarRoomResponse get(UUID tenantId, UUID warRoomId) {
        WarRoom warRoom = warRoomRepository.findById(warRoomId)
            .filter(w -> w.getTenantId().equals(tenantId))
            .orElseThrow(() -> new ResourceNotFoundException("War room not found: " + warRoomId));
        return toResponse(warRoom);
    }

    @Transactional(readOnly = true)
    public List<WarRoomResponse> list(UUID tenantId, WarRoomStatus status) {
        return warRoomRepository.findByTenantIdAndStatus(tenantId, status).stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<WarRoomResponse> listActive(UUID tenantId) {
        return list(tenantId, WarRoomStatus.ACTIVE);
    }

    private WarRoom requireByIdAndTenant(UUID warRoomId, UUID tenantId) {
        return warRoomRepository.findById(warRoomId)
            .filter(w -> w.getTenantId().equals(tenantId))
            .orElseThrow(() -> new ResourceNotFoundException("War room not found: " + warRoomId));
    }

    private WarRoomResponse toResponse(WarRoom warRoom) {
        List<WarRoomResponse.ParticipantResponse> participants = participantRepository
            .findByWarRoomIdAndLeftAtIsNull(warRoom.getId()).stream()
            .map(p -> WarRoomResponse.ParticipantResponse.builder()
                .userId(p.getUserId())
                .role(p.getRole())
                .joinedAt(p.getJoinedAt())
                .build())
            .toList();

        return WarRoomResponse.builder()
            .id(warRoom.getId())
            .tenantId(warRoom.getTenantId())
            .incidentId(warRoom.getIncidentId())
            .status(warRoom.getStatus())
            .openedAt(warRoom.getOpenedAt())
            .openedBy(warRoom.getOpenedBy())
            .closedAt(warRoom.getClosedAt())
            .slackChannelId(warRoom.getSlackChannelId())
            .slackThreadTs(warRoom.getSlackThreadTs())
            .participants(participants)
            .build();
    }
}
