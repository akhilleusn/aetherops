package com.aetherops.runbook.service;

import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.runbook.model.RunbookGenerationResult;
import com.aetherops.runbook.model.RunbookPhase;
import com.aetherops.runbook.model.RunbookStep;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic runbook generator used until a real LLM integration is wired in.
 * Swap by adding another {@link RunbookGenerator} bean marked {@code @Primary}.
 *
 * All steps are read-only and observational — no destructive or autonomous actions.
 * Evidence-grounded INVESTIGATE steps cite the real evidence item ID from the pack.
 */
@Service
public class MockRunbookGenerator implements RunbookGenerator {

    private static final String GENERATOR_VERSION = "mock-v1";

    @Override
    public RunbookGenerationResult generate(AIAnalysisContext context) {
        List<RunbookStep> steps = new ArrayList<>();
        steps.addAll(buildTriageSteps(context));
        steps.addAll(buildInvestigateSteps(context));
        steps.addAll(buildVerifySteps(context));

        return RunbookGenerationResult.builder()
            .steps(steps)
            .generationBasis(detectGenerationBasis(context))
            .generatorVersion(GENERATOR_VERSION)
            .build();
    }

    private List<RunbookStep> buildTriageSteps(AIAnalysisContext context) {
        String service = context.getServiceId() != null ? context.getServiceId() : "the affected service";
        List<RunbookStep> steps = new ArrayList<>();

        steps.add(RunbookStep.builder()
            .phase(RunbookPhase.TRIAGE)
            .action("Confirm the current operational status of " + service
                + " by checking its health endpoint or status page.")
            .rationale("The incident is attributed to " + service
                + ". Establishing whether the service is still running is the first safe, read-only check.")
            .build());

        steps.add(RunbookStep.builder()
            .phase(RunbookPhase.TRIAGE)
            .action("Verify the recorded severity (" + context.getSeverity()
                + ") matches the observed customer impact, and escalate if it is understated.")
            .rationale("Incident severity determines the urgency and breadth of the response. An underrated severity risks insufficient engagement from the right teams.")
            .build());

        steps.add(RunbookStep.builder()
            .phase(RunbookPhase.TRIAGE)
            .action("Ensure the on-call engineer for " + service + " is aware of the incident.")
            .rationale("Early notification prevents duplicated investigation effort and ensures the engineer with the most context on "
                + service + " is engaged from the start.")
            .build());

        if ("P1".equals(context.getSeverity()) || "P2".equals(context.getSeverity())) {
            steps.add(RunbookStep.builder()
                .phase(RunbookPhase.TRIAGE)
                .action("Open a dedicated incident channel for this " + context.getSeverity()
                    + " incident and share the incident ID with all responders.")
                .rationale("High-severity incidents require coordinated, real-time communication. A dedicated channel preserves the investigation timeline and prevents critical information from being lost.")
                .build());
        }

        return steps;
    }

    private List<RunbookStep> buildInvestigateSteps(AIAnalysisContext context) {
        String service = context.getServiceId() != null ? context.getServiceId() : "the affected service";
        List<RunbookStep> steps = new ArrayList<>();

        // One evidence-grounded step per evidence item — cites the real evidence item ID
        for (AIAnalysisContext.EvidenceContext item : context.getEvidenceItems()) {
            steps.add(RunbookStep.builder()
                .phase(RunbookPhase.INVESTIGATE)
                .action("Review evidence item " + item.getEvidenceItemId()
                    + " — a " + item.getLevel() + "-level log event: \""
                    + item.getSummary() + "\".")
                .rationale("This log event at " + item.getTimestamp()
                    + " is part of the incident evidence pack. Read it in its surrounding log context"
                    + " to understand the sequence of events that led to or followed from this entry.")
                .evidenceRef(item.getEvidenceItemId())
                .build());
        }

        steps.add(RunbookStep.builder()
            .phase(RunbookPhase.INVESTIGATE)
            .action("Search the " + service + " log stream for correlated errors in the 30 minutes"
                + " before and after the incident window.")
            .rationale("Evidence items represent a filtered subset of log output. Adjacent log lines"
                + " may contain the root cause event or reveal cascading failures not yet captured as evidence.")
            .build());

        steps.add(RunbookStep.builder()
            .phase(RunbookPhase.INVESTIGATE)
            .action("Review the deployment history for " + service
                + " in the 24 hours before the incident was first observed ("
                + context.getFirstSeen() + ").")
            .rationale("A code, configuration, or dependency change shortly before the incident window"
                + " is a high-probability candidate for root cause. Rule it out before investigating further.")
            .build());

        steps.add(RunbookStep.builder()
            .phase(RunbookPhase.INVESTIGATE)
            .action("Inspect the health of upstream and downstream dependencies of " + service
                + " — databases, message queues, caches, and external APIs.")
            .rationale("Errors in " + service + " often propagate from a dependency rather than"
                + " originating within the service itself. Ruling out dependencies first narrows the investigation scope.")
            .build());

        return steps;
    }

    private List<RunbookStep> buildVerifySteps(AIAnalysisContext context) {
        String service = context.getServiceId() != null ? context.getServiceId() : "the affected service";
        List<RunbookStep> steps = new ArrayList<>();

        steps.add(RunbookStep.builder()
            .phase(RunbookPhase.VERIFY)
            .action("Cross-reference the suspected root cause against at least two independent"
                + " evidence items before concluding the investigation.")
            .rationale("A root cause derived from a single log event may be a symptom rather than"
                + " the cause. Triangulating across multiple evidence items reduces the risk of acting on a misdiagnosis.")
            .build());

        steps.add(RunbookStep.builder()
            .phase(RunbookPhase.VERIFY)
            .action("Document the confirmed root cause, the affected component(s) of " + service
                + ", and the full evidence trail in the incident record.")
            .rationale("A written, evidence-backed root cause statement prevents conflicting interpretations"
                + " between responders and forms the basis for a structured post-incident review.")
            .build());

        steps.add(RunbookStep.builder()
            .phase(RunbookPhase.VERIFY)
            .action("Check whether any other services that depend on or are depended upon by "
                + service + " have been silently affected by the same root cause.")
            .rationale("Incidents with a shared dependency can affect multiple services simultaneously."
                + " A sweep of related services prevents secondary incidents from being missed while the primary is being resolved.")
            .build());

        return steps;
    }

    private String detectGenerationBasis(AIAnalysisContext context) {
        if (context.getEvidenceItems().isEmpty()) {
            return "no-evidence";
        }
        boolean allLogs = context.getEvidenceItems().stream()
            .allMatch(item -> "LOG_EVENT".equals(item.getType()));
        return allLogs ? "log-only" : "mixed-evidence";
    }
}
