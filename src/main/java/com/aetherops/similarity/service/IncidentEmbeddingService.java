package com.aetherops.similarity.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.similarity.model.IncidentEmbedding;
import com.aetherops.similarity.model.SimilarIncidentContext;
import com.aetherops.similarity.repository.IncidentEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentEmbeddingService {

    static final int TOP_K = 3;

    private final IncidentEmbeddingRepository embeddingRepository;
    private final IncidentRepository incidentRepository;
    private final EmbeddingProvider embeddingProvider;

    /**
     * Generate and persist an embedding for the given incident.
     * Idempotent: re-indexing overwrites the previous entry.
     */
    @Transactional
    public void indexIncident(Incident incident) {
        String text = buildIndexText(incident);
        float[] embedding = embeddingProvider.embed(text);

        IncidentEmbedding entry = IncidentEmbedding.builder()
                .incidentId(incident.getId())
                .tenantId(incident.getTenantId())
                .embedding(embedding)
                .indexedText(text)
                .embeddingModel(embeddingProvider.modelName())
                .build();

        embeddingRepository.save(entry);
        log.info("Indexed embedding incidentId={} tenantId={} model={}",
                incident.getId(), incident.getTenantId(), embeddingProvider.modelName());
    }

    @Transactional
    public void indexIncident(UUID incidentId, UUID tenantId) {
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + incidentId));
        indexIncident(incident);
    }

    /**
     * Find the top-K resolved incidents most similar to the given incident,
     * scored by cosine similarity of their embeddings.
     * Returns an empty list if no candidates have been indexed.
     */
    @Transactional(readOnly = true)
    public List<SimilarIncidentContext> findSimilar(UUID queryIncidentId, UUID tenantId) {
        Incident queryIncident = incidentRepository.findById(queryIncidentId).orElse(null);
        if (queryIncident == null) return List.of();

        float[] queryEmbedding = embeddingProvider.embed(buildIndexText(queryIncident));
        if (LocalEmbeddingProvider.isZeroVector(queryEmbedding)) return List.of();

        List<Incident> resolvedCandidates = incidentRepository
                .findByTenantIdAndStatus(tenantId, IncidentStatus.RESOLVED)
                .stream()
                .filter(i -> !i.getId().equals(queryIncidentId))
                .toList();

        if (resolvedCandidates.isEmpty()) return List.of();

        return resolvedCandidates.stream()
                .map(candidate -> {
                    IncidentEmbedding emb = embeddingRepository
                            .findById(candidate.getId())
                            .orElse(null);
                    if (emb == null) return null;

                    double score = LocalEmbeddingProvider.cosineSimilarity(
                            queryEmbedding, emb.getEmbedding());
                    if (score <= 0.0) return null;

                    return SimilarIncidentContext.builder()
                            .incidentId(candidate.getId())
                            .title(candidate.getTitle())
                            .serviceId(candidate.getServiceId())
                            .severity(candidate.getSeverity() != null
                                    ? candidate.getSeverity().name() : null)
                            .resolutionNotes(candidate.getResolutionNotes())
                            .similarityScore(score)
                            .resolvedAt(candidate.getResolvedAt())
                            .build();
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(SimilarIncidentContext::getSimilarityScore).reversed())
                .limit(TOP_K)
                .toList();
    }

    public String buildIndexText(Incident incident) {
        StringBuilder sb = new StringBuilder();
        if (incident.getTitle()           != null) sb.append(incident.getTitle()).append(' ');
        if (incident.getDescription()     != null) sb.append(incident.getDescription()).append(' ');
        if (incident.getServiceId()       != null) sb.append(incident.getServiceId()).append(' ');
        if (incident.getResolutionNotes() != null) sb.append(incident.getResolutionNotes());
        return sb.toString().trim();
    }
}
