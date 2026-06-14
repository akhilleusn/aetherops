package com.aetherops.usage;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String meter;

    @Column(nullable = false)
    private long quantity;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant recordedAt;
}