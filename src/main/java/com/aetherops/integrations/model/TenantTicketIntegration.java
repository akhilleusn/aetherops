package com.aetherops.integrations.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_ticket_integrations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantTicketIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketProvider provider;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(name = "project_key", length = 50)
    private String projectKey;

    @Column(name = "encrypted_api_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedApiToken;

    @Column(length = 255)
    private String username;

    @Column(nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
