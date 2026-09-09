package com.aetherops.auth.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String tokenHash;

    /** Groups every token descended from one original login, so the whole chain can be
     * revoked at once when a reused (already-rotated-away) token reveals a leak. */
    @Column(nullable = false)
    private UUID familyId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    /** Set true the moment this token is rotated away from. Presenting a used token again is
     * reuse — the signal that it (or another in its family) has leaked. */
    @Column(nullable = false)
    private boolean used;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}