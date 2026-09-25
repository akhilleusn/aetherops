package com.aetherops.publicdemo;

import com.aetherops.analysis.model.AIAnalysisResponse;
import com.aetherops.analysis.model.AnalysisStatus;
import com.aetherops.analysis.service.AIAnalysisService;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.evidence.model.EvidenceItemResponse;
import com.aetherops.evidence.service.EvidencePackService;
import com.aetherops.incident.service.IncidentService;
import com.aetherops.ingestion.model.LogEvent;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.oncall.IncidentTimeline;
import com.aetherops.oncall.IncidentTimelineService;
import com.aetherops.postmortem.service.PostmortemService;
import com.aetherops.runbook.service.RunbookService;
import com.aetherops.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Every read the public demo can perform. Each public method is @Transactional(readOnly = true)
 * for two reasons: TenantContextInterceptor only sets app.tenant_id (RLS) on entry to a method
 * annotated @Transactional, and a read-only transaction is read-only in Postgres too, so a write
 * reached from here -- through a bug in any service this delegates to -- fails in the database
 * instead of succeeding. PublicDemoSecurityTest checks both.
 *
 * The tenant is always the configured demo tenant; nothing here takes a tenant from the request.
 */
@Service
@RequiredArgsConstructor
public class PublicDemoService {

    static final int PAGE_SIZE     = 20;
    static final int MAX_LOG_LINES = 500;

    private final PublicDemoProperties    properties;
    private final IncidentService         incidentService;
    private final IncidentTimelineService timelineService;
    private final EvidencePackService     evidencePackService;
    private final LogEventRepository      logEventRepository;
    private final AIAnalysisService       aiAnalysisService;
    private final RunbookService          runbookService;
    private final PostmortemService       postmortemService;

    @Transactional(readOnly = true)
    public List<DemoViews.Incident> listIncidents(int page) {
        PageRequest pageRequest = PageRequest.of(Math.max(0, page), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "lastSeenAt"));
        return incidentService.findAll(demoTenantId(), null, null, null, null, pageRequest)
            .map(DemoViews.Incident::from)
            .getContent();
    }

    @Transactional(readOnly = true)
    public DemoViews.Incident getIncident(UUID incidentId) {
        demoTenantId();
        return DemoViews.Incident.from(incidentService.findById(incidentId));
    }

    @Transactional(readOnly = true)
    public List<DemoViews.TimelineEvent> getTimeline(UUID incidentId) {
        UUID tenantId = demoTenantId();
        incidentService.findById(incidentId);   // 404 unless it's a demo-tenant incident
        return timelineService.findForIncident(tenantId, incidentId).stream()
            // Slack button presses record the presented Slack identity -- never public.
            .filter(e -> e.getEventType() != IncidentTimeline.TimelineEventType.SLACK_ACTION_REQUESTED)
            .map(DemoViews.TimelineEvent::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<DemoViews.LogLine> getLogLines(UUID incidentId) {
        UUID tenantId = demoTenantId();
        List<UUID> logEventIds = evidencePackService.getEvidencePack(incidentId, tenantId).getEvidence().stream()
            .map(EvidenceItemResponse::getLogEventId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        return logEventRepository.findAllById(logEventIds).stream()
            .filter(e -> tenantId.equals(e.getTenantId()))
            .sorted(Comparator.comparing(LogEvent::getOccurredAt))
            .limit(MAX_LOG_LINES)
            .map(DemoViews.LogLine::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public DemoViews.Analysis getLatestAnalysis(UUID incidentId) {
        AIAnalysisResponse analysis = aiAnalysisService.getLatest(incidentId, demoTenantId());
        // A failed analysis carries internal error text (e.g. "Anthropic API call failed: ...").
        if (analysis.getStatus() != AnalysisStatus.COMPLETED) {
            throw new ResourceNotFoundException("No analysis found for incident " + incidentId);
        }
        return DemoViews.Analysis.from(analysis);
    }

    @Transactional(readOnly = true)
    public DemoViews.Runbook getLatestRunbook(UUID incidentId) {
        return DemoViews.Runbook.from(runbookService.getLatest(incidentId, demoTenantId()));
    }

    @Transactional(readOnly = true)
    public DemoViews.Postmortem getLatestPostmortem(UUID incidentId) {
        return DemoViews.Postmortem.from(postmortemService.getLatest(incidentId, demoTenantId()));
    }

    // Also asserts PublicDemoFilter pinned this request's TenantContext to the demo tenant, so a
    // call from anywhere else (or a filter-ordering regression) fails instead of reading.
    private UUID demoTenantId() {
        UUID tenantId = properties.tenantId().orElseThrow(() -> new ResourceNotFoundException("Not found"));
        if (!tenantId.equals(TenantContext.get())) {
            throw new IllegalStateException("Public demo read without the demo TenantContext");
        }
        return tenantId;
    }
}
