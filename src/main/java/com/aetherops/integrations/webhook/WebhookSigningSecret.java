package com.aetherops.integrations.webhook;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant's opt-in signing secret for one inbound webhook provider. Existence of an
 * enabled row for (tenantId, provider) is what makes {@link WebhookSignatureFilter} start
 * requiring a valid signature on that provider's endpoint for that tenant, instead of
 * falling back to X-Api-Key-only auth.
 */
@Entity
@Table(name = "inbound_webhook_signing_secrets")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookSigningSecret {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookSigningProvider provider;

    @Column(name = "encrypted_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedSecret;

    /** Grafana only — the signature header name the sender is configured to use; null = provider default. */
    @Column(name = "header_name", length = 100)
    private String headerName;

    /** Grafana only — the replay-protection timestamp header name, if the sender has one configured. */
    @Column(name = "timestamp_header_name", length = 100)
    private String timestampHeaderName;

    @Column(nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
