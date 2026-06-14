package com.aetherops.saga;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saga_steps")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SagaStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "saga_id", nullable = false)
    private UUID sagaId;

    @Column(name = "step_name", nullable = false, length = 100)
    private String stepName;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
