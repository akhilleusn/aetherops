package com.aetherops.retention.service;

import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.postmortem.repository.PostmortemDraftRepository;
import com.aetherops.retention.model.RetentionResult;
import com.aetherops.runbook.repository.RunbookSuggestionRepository;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionService {

    private final TenantRepository           tenantRepository;
    private final LogEventRepository         logEventRepository;
    private final EvidenceItemRepository     evidenceItemRepository;
    private final IncidentRepository         incidentRepository;
    private final AIAnalysisRepository       aiAnalysisRepository;
    private final RunbookSuggestionRepository runbookSuggestionRepository;
    private final PostmortemDraftRepository  postmortemDraftRepository;

    /**
     * Runs the retention policy for the given tenant.
     *
     * dryRun=true  — counts eligible rows per table, deletes nothing.
     * dryRun=false — deletes in dependency order (derived records first,
     *                base records last), returns counts of rows deleted.
     *
     * Every count and delete query is scoped to the supplied tenantId.
     * No other tenant's data is ever touched.
     */
    @Transactional
    public RetentionResult run(UUID tenantId, boolean dryRun) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        Instant cutoff = Instant.now().minus(tenant.getRetentionDays(), ChronoUnit.DAYS);

        long postmortems  = postmortemDraftRepository  .countByTenantIdAndCreatedAtBefore(tenantId, cutoff);
        long runbooks     = runbookSuggestionRepository.countByTenantIdAndCreatedAtBefore(tenantId, cutoff);
        long analyses     = aiAnalysisRepository       .countByTenantIdAndCreatedAtBefore(tenantId, cutoff);
        long evidence     = evidenceItemRepository     .countByTenantIdAndCreatedAtBefore(tenantId, cutoff);
        long incidents    = incidentRepository         .countByTenantIdAndLastSeenAtBefore(tenantId, cutoff);
        long logEvents    = logEventRepository         .countByTenantIdAndIngestedAtBefore(tenantId, cutoff);

        if (!dryRun) {
            postmortemDraftRepository  .deleteByTenantIdAndCreatedAtBefore(tenantId, cutoff);
            runbookSuggestionRepository.deleteByTenantIdAndCreatedAtBefore(tenantId, cutoff);
            aiAnalysisRepository       .deleteByTenantIdAndCreatedAtBefore(tenantId, cutoff);
            evidenceItemRepository     .deleteByTenantIdAndCreatedAtBefore(tenantId, cutoff);
            incidentRepository         .deleteByTenantIdAndLastSeenAtBefore(tenantId, cutoff);
            logEventRepository         .deleteByTenantIdAndIngestedAtBefore(tenantId, cutoff);

            log.info("Retention run: tenant={} retentionDays={} cutoff={} "
                + "deleted: logEvents={} evidence={} incidents={} analyses={} runbooks={} postmortems={}",
                tenantId, tenant.getRetentionDays(), cutoff,
                logEvents, evidence, incidents, analyses, runbooks, postmortems);
        } else {
            log.info("Retention dry-run: tenant={} retentionDays={} cutoff={} "
                + "eligible: logEvents={} evidence={} incidents={} analyses={} runbooks={} postmortems={}",
                tenantId, tenant.getRetentionDays(), cutoff,
                logEvents, evidence, incidents, analyses, runbooks, postmortems);
        }

        long total = logEvents + evidence + incidents + analyses + runbooks + postmortems;

        return RetentionResult.builder()
            .tenantId(tenantId)
            .retentionDays(tenant.getRetentionDays())
            .cutoffDate(cutoff)
            .dryRun(dryRun)
            .logEventsAffected(logEvents)
            .evidenceItemsAffected(evidence)
            .incidentsAffected(incidents)
            .analysesAffected(analyses)
            .runbooksAffected(runbooks)
            .postmortemsAffected(postmortems)
            .totalAffected(total)
            .build();
    }
}
