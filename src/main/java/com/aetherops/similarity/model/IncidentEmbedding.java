package com.aetherops.similarity.model;

import com.aetherops.common.converter.FloatArrayConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incident_embeddings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentEmbedding {

    @Id
    @Column(name = "incident_id")
    private UUID incidentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Convert(converter = FloatArrayConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private float[] embedding;

    @Column(name = "indexed_text", nullable = false, columnDefinition = "TEXT")
    private String indexedText;

    @Column(name = "embedding_model", nullable = false, length = 100)
    private String embeddingModel;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
