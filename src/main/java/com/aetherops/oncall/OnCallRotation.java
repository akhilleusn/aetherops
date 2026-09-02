package com.aetherops.oncall;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "oncall_rotation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnCallRotation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private UUID tenantId;
    private String serviceId;
    private String rotationName;
    private Instant rotationStartAt;
    private Integer shiftLengthHours;
    
    @ElementCollection
    @OrderColumn(name = "participant_order")
    @CollectionTable(name = "oncall_rotation_participants", joinColumns = @JoinColumn(name = "oncall_rotation_id"))
    @Column(name = "participants")
    private List<String> participants;

    /**
     * Optional, same-order/same-length mapping from {@link #participants} to a User id, used
     * only to consult PTO/calendar availability (Phase 7) before paging. Sparse by design —
     * existing rotations have none of these rows, and any index without one is treated as
     * "no availability info", same as always-available.
     */
    @ElementCollection
    @OrderColumn(name = "participant_order")
    @CollectionTable(name = "oncall_rotation_participant_user_ids", joinColumns = @JoinColumn(name = "oncall_rotation_id"))
    @Column(name = "participant_user_id")
    private List<UUID> participantUserIds;

    public String currentOnCallPhoneNumber() {
        return currentOnCallPhoneNumber(userId -> true);
    }

    /**
     * Same rotation-index logic as {@link #currentOnCallPhoneNumber()}, but skips forward past
     * a scheduled participant the given predicate reports unavailable (e.g. on PTO), wrapping
     * around at most once. Falls back to the originally-scheduled participant if everyone in
     * the rotation is unavailable — paging the "wrong" person beats paging no one.
     */
    public String currentOnCallPhoneNumber(java.util.function.Predicate<UUID> isAvailable) {
        if (participants == null || participants.isEmpty()) return null;
        long hoursSinceStart = java.time.Duration.between(rotationStartAt, Instant.now()).toHours();
        int size = participants.size();
        int startIndex = (int) (hoursSinceStart / shiftLengthHours) % size;

        for (int offset = 0; offset < size; offset++) {
            int index = (startIndex + offset) % size;
            UUID candidateUserId = (participantUserIds != null && index < participantUserIds.size())
                ? participantUserIds.get(index) : null;
            if (candidateUserId == null || isAvailable.test(candidateUserId)) {
                return participants.get(index);
            }
        }
        return participants.get(startIndex);
    }
}
