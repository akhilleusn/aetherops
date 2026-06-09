package com.aetherops.incident.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"idempotency_key", "tenant_id", "resource_type"}
        ))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String resourceType;

    private UUID resourceId;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}