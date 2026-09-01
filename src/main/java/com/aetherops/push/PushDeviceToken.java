package com.aetherops.push;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "push_device_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushDeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Expo push token, e.g. "ExponentPushToken[xxxxxxxx]". */
    @Column(name = "expo_push_token", nullable = false, unique = true, length = 255)
    private String expoPushToken;

    @Column(length = 20)
    private String platform;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
