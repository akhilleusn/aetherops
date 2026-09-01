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
    private List<String> participants;
    
    public String currentOnCallPhoneNumber() {
        if (participants == null || participants.isEmpty()) return null;
        long hoursSinceStart = java.time.Duration.between(rotationStartAt, Instant.now()).toHours();
        int currentIndex = (int) (hoursSinceStart / shiftLengthHours) % participants.size();
        return participants.get(currentIndex);
    }
}
