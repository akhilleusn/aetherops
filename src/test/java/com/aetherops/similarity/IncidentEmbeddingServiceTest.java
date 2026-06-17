package com.aetherops.similarity;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.similarity.model.IncidentEmbedding;
import com.aetherops.similarity.model.SimilarIncidentContext;
import com.aetherops.similarity.repository.IncidentEmbeddingRepository;
import com.aetherops.similarity.service.IncidentEmbeddingService;
import com.aetherops.similarity.service.LocalEmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentEmbeddingServiceTest {

    @Mock private IncidentEmbeddingRepository embeddingRepository;
    @Mock private IncidentRepository          incidentRepository;

    private final LocalEmbeddingProvider embeddingProvider = new LocalEmbeddingProvider();
    private IncidentEmbeddingService     service;

    private static final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new IncidentEmbeddingService(
                embeddingRepository, incidentRepository, embeddingProvider);
    }

    // ── indexIncident ────────────────────────────────────────────────────────

    @Test
    void indexIncident_savesEmbeddingWithCorrectFields() {
        Incident incident = resolvedIncident(UUID.randomUUID(),
                "Database connection pool exhausted",
                "HikariCP pool saturation on payment-svc. All connections held by slow queries.",
                "payment-svc",
                "Increased pool size to 20 and added slow-query log alerting.");

        service.indexIncident(incident);

        verify(embeddingRepository).save(argThat(emb ->
                emb.getIncidentId().equals(incident.getId())
                && emb.getTenantId().equals(TENANT_ID)
                && emb.getEmbeddingModel().equals(LocalEmbeddingProvider.MODEL_NAME)
                && emb.getEmbedding() != null
                && emb.getEmbedding().length == LocalEmbeddingProvider.dimensions()
        ));
    }

    // ── findSimilar ──────────────────────────────────────────────────────────

    @Test
    void findSimilar_queryIncidentNotFound_returnsEmpty() {
        UUID queryId = UUID.randomUUID();
        when(incidentRepository.findById(queryId)).thenReturn(Optional.empty());

        List<SimilarIncidentContext> result = service.findSimilar(queryId, TENANT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void findSimilar_noResolvedCandidates_returnsEmpty() {
        Incident query = openIncident(UUID.randomUUID(), "Redis cache miss spike", "cache-svc");
        when(incidentRepository.findById(query.getId())).thenReturn(Optional.of(query));
        when(incidentRepository.findByTenantIdAndStatus(TENANT_ID, IncidentStatus.RESOLVED))
                .thenReturn(List.of());

        List<SimilarIncidentContext> result = service.findSimilar(query.getId(), TENANT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void findSimilar_candidateHasNoEmbedding_excluded() {
        Incident query     = openIncident(UUID.randomUUID(), "Database timeout on payment-svc", "payment-svc");
        Incident candidate = resolvedIncident(UUID.randomUUID(), "Database connection pool full",
                "HikariCP exhausted", "payment-svc", "Increased pool size");
        when(incidentRepository.findById(query.getId())).thenReturn(Optional.of(query));
        when(incidentRepository.findByTenantIdAndStatus(TENANT_ID, IncidentStatus.RESOLVED))
                .thenReturn(List.of(candidate));
        when(embeddingRepository.findById(candidate.getId())).thenReturn(Optional.empty());

        List<SimilarIncidentContext> result = service.findSimilar(query.getId(), TENANT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void findSimilar_similarCandidate_returnedWithScore() {
        Incident query = openIncident(UUID.randomUUID(),
                "Database connection pool exhausted — payment service", "payment-svc");
        Incident candidate = resolvedIncident(UUID.randomUUID(),
                "HikariCP pool saturation — database connections blocked",
                "All connections held by slow JDBC queries",
                "payment-svc",
                "Raised pool size from 10 to 20 and added idle-timeout tuning.");

        float[] candidateEmb = embeddingProvider.embed(
                service.buildIndexText(candidate));
        IncidentEmbedding stored = IncidentEmbedding.builder()
                .incidentId(candidate.getId())
                .tenantId(TENANT_ID)
                .embedding(candidateEmb)
                .indexedText("HikariCP pool saturation database connections blocked payment-svc")
                .embeddingModel(LocalEmbeddingProvider.MODEL_NAME)
                .build();

        when(incidentRepository.findById(query.getId())).thenReturn(Optional.of(query));
        when(incidentRepository.findByTenantIdAndStatus(TENANT_ID, IncidentStatus.RESOLVED))
                .thenReturn(List.of(candidate));
        when(embeddingRepository.findById(candidate.getId())).thenReturn(Optional.of(stored));

        List<SimilarIncidentContext> result = service.findSimilar(query.getId(), TENANT_ID);

        assertThat(result).hasSize(1);
        SimilarIncidentContext ctx = result.get(0);
        assertThat(ctx.getIncidentId()).isEqualTo(candidate.getId());
        assertThat(ctx.getSimilarityScore()).isGreaterThan(0.0);
        assertThat(ctx.getResolutionNotes()).isEqualTo(candidate.getResolutionNotes());
    }

    @Test
    void findSimilar_queryIncidentExcludedFromResults() {
        UUID queryId = UUID.randomUUID();
        Incident query = resolvedIncident(queryId, "Database connection pool exhausted",
                "HikariCP saturation", "payment-svc", "Increased pool size");
        float[] emb = embeddingProvider.embed(service.buildIndexText(query));

        when(incidentRepository.findById(queryId)).thenReturn(Optional.of(query));
        when(incidentRepository.findByTenantIdAndStatus(TENANT_ID, IncidentStatus.RESOLVED))
                .thenReturn(List.of(query));

        List<SimilarIncidentContext> result = service.findSimilar(queryId, TENANT_ID);

        // Query incident is filtered out by ID even if it's in the resolved list
        assertThat(result).isEmpty();
        verify(embeddingRepository, never()).findById(any());
    }

    @Test
    void findSimilar_multipleCandidates_sortedByScoreDescending() {
        Incident query = openIncident(UUID.randomUUID(),
                "Database connection pool timeout postgres query", "user-svc");

        // High-relevance candidate (lots of overlapping keywords)
        Incident highRelevance = resolvedIncident(UUID.randomUUID(),
                "Database connection pool exhausted postgres query timeout",
                "HikariCP pool saturation on postgres database",
                "user-svc", "Increased pool size.");

        // Low-relevance candidate (kafka / messaging keywords, not DB)
        Incident lowRelevance = resolvedIncident(UUID.randomUUID(),
                "Kafka consumer lag high",
                "Consumer lag grew to 50k offsets on the audit topic",
                "kafka-svc", "Scaled consumer group.");

        float[] highEmb = embeddingProvider.embed(service.buildIndexText(highRelevance));
        float[] lowEmb  = embeddingProvider.embed(service.buildIndexText(lowRelevance));

        IncidentEmbedding highStored = IncidentEmbedding.builder()
                .incidentId(highRelevance.getId()).tenantId(TENANT_ID)
                .embedding(highEmb).indexedText("").embeddingModel(LocalEmbeddingProvider.MODEL_NAME).build();
        IncidentEmbedding lowStored = IncidentEmbedding.builder()
                .incidentId(lowRelevance.getId()).tenantId(TENANT_ID)
                .embedding(lowEmb).indexedText("").embeddingModel(LocalEmbeddingProvider.MODEL_NAME).build();

        when(incidentRepository.findById(query.getId())).thenReturn(Optional.of(query));
        when(incidentRepository.findByTenantIdAndStatus(TENANT_ID, IncidentStatus.RESOLVED))
                .thenReturn(List.of(highRelevance, lowRelevance));
        when(embeddingRepository.findById(highRelevance.getId())).thenReturn(Optional.of(highStored));
        when(embeddingRepository.findById(lowRelevance.getId())).thenReturn(Optional.of(lowStored));

        List<SimilarIncidentContext> result = service.findSimilar(query.getId(), TENANT_ID);

        assertThat(result).isNotEmpty();
        // Results should be sorted: highest similarity first
        if (result.size() >= 2) {
            assertThat(result.get(0).getSimilarityScore())
                    .isGreaterThanOrEqualTo(result.get(1).getSimilarityScore());
        }
        // High-relevance should appear before low-relevance
        assertThat(result.get(0).getIncidentId()).isEqualTo(highRelevance.getId());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Incident openIncident(UUID id, String title, String serviceId) {
        return Incident.builder()
                .id(id)
                .tenantId(TENANT_ID)
                .title(title)
                .description(title + " — detailed description.")
                .serviceId(serviceId)
                .status(IncidentStatus.OPEN)
                .severity(IncidentSeverity.HIGH)
                .fingerprint(id.toString().replace("-", ""))
                .eventCount(1)
                .firstSeenAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .lastSeenAt(Instant.now())
                .build();
    }

    private Incident resolvedIncident(UUID id, String title, String description,
                                      String serviceId, String resolutionNotes) {
        return Incident.builder()
                .id(id)
                .tenantId(TENANT_ID)
                .title(title)
                .description(description)
                .serviceId(serviceId)
                .resolutionNotes(resolutionNotes)
                .status(IncidentStatus.RESOLVED)
                .severity(IncidentSeverity.MEDIUM)
                .fingerprint(id.toString().replace("-", ""))
                .eventCount(3)
                .firstSeenAt(Instant.now().minus(2, ChronoUnit.DAYS))
                .lastSeenAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .resolvedAt(Instant.now().minus(20, ChronoUnit.HOURS))
                .build();
    }
}
