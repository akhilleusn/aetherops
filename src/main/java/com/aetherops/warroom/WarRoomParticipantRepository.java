package com.aetherops.warroom;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WarRoomParticipantRepository extends JpaRepository<WarRoomParticipant, UUID> {

    List<WarRoomParticipant> findByWarRoomIdAndLeftAtIsNull(UUID warRoomId);

    Optional<WarRoomParticipant> findByWarRoomIdAndUserIdAndLeftAtIsNull(UUID warRoomId, UUID userId);
}
