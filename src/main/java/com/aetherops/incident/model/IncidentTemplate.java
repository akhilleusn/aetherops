package com.aetherops.incident.model;

import com.aetherops.common.converter.StringMapConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A null tenantId marks a global, system-provided template visible to every tenant. */
@Entity
@Table(name = "incident_templates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "default_title", length = 500)
    private String defaultTitle;

    @Column(name = "default_description", columnDefinition = "TEXT")
    private String defaultDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_severity", length = 20)
    private IncidentSeverity defaultSeverity;

    @Column(name = "default_service_id", length = 100)
    private String defaultServiceId;

    @Convert(converter = StringMapConverter.class)
    @Column(name = "default_tags", columnDefinition = "TEXT")
    private Map<String, String> defaultTags;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
