package com.aetherops.auth.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", length = 60)
    private String passwordHash;

    @Column(name = "google_sub", length = 255, unique = true)
    private String googleSub;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "totp_secret", length = 512)
    private String totpSecret;

    @Column(name = "two_fa_enabled", nullable = false)
    private boolean twoFaEnabled;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "email_verification_token", length = 64, unique = true)
    private String emailVerificationToken;

    @Column(name = "email_verification_token_expires_at")
    private Instant emailVerificationTokenExpiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
