package com.aetherops.sso;

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
@Table(name = "tenant_sso_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantSsoConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "idp_entity_id", nullable = false, length = 500)
    private String idpEntityId;

    @Column(name = "idp_sso_url", nullable = false, length = 500)
    private String idpSsoUrl;

    /** PEM-encoded X.509 certificate the IdP signs SAML assertions with. */
    @Column(name = "idp_certificate", nullable = false, columnDefinition = "TEXT")
    private String idpCertificate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
