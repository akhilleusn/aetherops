package com.aetherops.warroom;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JoinWarRoomRequest {

    @NotNull(message = "userId is required")
    private UUID userId;

    @NotNull(message = "role is required")
    private ParticipantRole role;
}
