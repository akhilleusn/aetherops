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

    /** Expo push token, e.g. "ExponentPushToken[xxxxxxxx]". Unique per (tenantId, expoPushToken)
     *  -- see V67's javadoc on why not globally unique: the same physical device can
     *  legitimately be registered under more than one tenant. */
    @Column(name = "expo_push_token", nullable = false, length = 255)
    private String expoPushToken;

    @Column(length = 20)
    private String platform;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
