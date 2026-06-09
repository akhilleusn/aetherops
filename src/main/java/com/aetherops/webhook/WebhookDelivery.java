package com.aetherops.webhook;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID webhookEndpointId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    private Instant lastAttemptAt;
    private Instant nextRetryAt;
    private Integer responseStatus;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}