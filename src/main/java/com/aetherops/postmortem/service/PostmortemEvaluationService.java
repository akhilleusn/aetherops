package com.aetherops.postmortem.service;

import com.aetherops.analysis.model.RiskLevel;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.postmortem.model.PostmortemDraftResponse;
import com.aetherops.postmortem.model.PostmortemEvaluationResult;
import com.aetherops.postmortem.model.TimelineEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Validates a postmortem draft for safety and structural completeness.
 * Pure logic — no DB, no HTTP, fully unit-testable with new PostmortemEvaluationService().
 */
@Service
public class PostmortemEvaluationService {

    private static final int TOTAL_CHECKS = 5;

    private static final List<String> UNSAFE_PATTERNS = List.of(
        "drop table", "delete database", "truncate", "rm -rf",
        "disable authentication", "expose secret", "print token",
        "rotate credentials automatically", "restart production repeatedly",
        "kill process", "shutdown service", "wipe", "purge all", "flush all", "format disk"
    );

    private static final List<Pattern> SECRET_PATTERNS = List.of(
        Pattern.compile("(?i)bearer\\s+(?!\\[REDACTED\\])[A-Za-z0-9\\-._~+/]+=*"),
        Pattern.compile("(?i)(?:password|api_key|token|secret)\\s*[=:]\\s*(?!\\[REDACTED\\])[^\\s&,;\"'\\n]+"),
        Pattern.compile("AKIA[0-9A-Z]{16}"),
        Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    );

    public PostmortemEvaluationResult evaluate(PostmortemDraftResponse draft, IncidentEvidencePackResponse pack) {
        List<String> issues    = new ArrayList<>();
        RiskLevel    worstRisk = RiskLevel.LOW;

        worstRisk = max(worstRisk, check1_requiredSectionsNotBlank(draft, issues));
        worstRisk = max(worstRisk, check2_timelineEntriesHaveDescriptions(draft, issues));
        worstRisk = max(worstRisk, check3_noSecretLeakage(draft, issues));
        worstRisk = max(worstRisk, check4_noDestructiveFollowUpActions(draft, issues));
        worstRisk = max(worstRisk, check5_timelineEvidenceRefsAreReal(draft, pack, issues));

        return PostmortemEvaluationResult.builder()
            .passed(issues.isEmpty())
            .issues(List.copyOf(issues))
            .riskLevel(worstRisk)
            .checkedAt(Instant.now())
            .checksPerformed(TOTAL_CHECKS)
            .build();
    }

    // Check 1: required narrative sections must not be blank
    private RiskLevel check1_requiredSectionsNotBlank(PostmortemDraftResponse draft, List<String> issues) {
        RiskLevel risk = RiskLevel.LOW;
        if (isBlank(draft.getIncidentSummary())) {
            issues.add("incidentSummary is blank — this section is required in every postmortem draft.");
            risk = RiskLevel.MEDIUM;
        }
        if (isBlank(draft.getLikelyCause())) {
            issues.add("likelyCause is blank — this section must contain either a confirmed cause or an explicit statement that the cause is unknown.");
            risk = RiskLevel.MEDIUM;
        }
        if (isBlank(draft.getImpact())) {
            issues.add("impact is blank — this section is required in every postmortem draft.");
            risk = RiskLevel.MEDIUM;
        }
        return risk;
    }

    // Check 2: every timeline entry must have a non-blank description
    private RiskLevel check2_timelineEntriesHaveDescriptions(PostmortemDraftResponse draft, List<String> issues) {
        if (draft.getTimeline() == null || draft.getTimeline().isEmpty()) {
            return RiskLevel.LOW;
        }
        RiskLevel risk = RiskLevel.LOW;
        for (int i = 0; i < draft.getTimeline().size(); i++) {
            TimelineEntry entry = draft.getTimeline().get(i);
            if (isBlank(entry.getDescription())) {
                issues.add("Timeline entry " + (i + 1) + " has a blank description — every timeline entry must describe what happened.");
                risk = RiskLevel.MEDIUM;
            }
        }
        return risk;
    }

    // Check 3: no raw credentials or tokens in any postmortem text field
    private RiskLevel check3_noSecretLeakage(PostmortemDraftResponse draft, List<String> issues) {
        for (String text : collectAllText(draft)) {
            for (Pattern pattern : SECRET_PATTERNS) {
                if (pattern.matcher(text).find()) {
                    issues.add("Postmortem draft contains a potential raw credential or token — "
                        + "all sensitive values must be masked as [REDACTED] before inclusion in a postmortem.");
                    return RiskLevel.HIGH;
                }
            }
        }
        return RiskLevel.LOW;
    }

    // Check 4: follow-up actions must not contain destructive or unsafe patterns
    private RiskLevel check4_noDestructiveFollowUpActions(PostmortemDraftResponse draft, List<String> issues) {
        if (draft.getFollowUpActions() == null) return RiskLevel.LOW;
        RiskLevel risk = RiskLevel.LOW;
        for (String action : draft.getFollowUpActions()) {
            String lower = action.toLowerCase();
            for (String pattern : UNSAFE_PATTERNS) {
                if (lower.contains(pattern)) {
                    issues.add("Follow-up action contains unsafe pattern '" + pattern
                        + "': \"" + action + "\" — follow-up actions must be safe and non-destructive.");
                    risk = RiskLevel.HIGH;
                }
            }
        }
        return risk;
    }

    // Check 5: evidenceRef values in timeline entries must match real evidence items in the pack
    private RiskLevel check5_timelineEvidenceRefsAreReal(
        PostmortemDraftResponse draft, IncidentEvidencePackResponse pack, List<String> issues
    ) {
        if (draft.getTimeline() == null) return RiskLevel.LOW;
        Set<String> realIds = pack.getEvidence().stream()
            .map(e -> e.getId().toString())
            .collect(Collectors.toSet());

        RiskLevel risk = RiskLevel.LOW;
        for (TimelineEntry entry : draft.getTimeline()) {
            if (entry.getEvidenceRef() != null && !entry.getEvidenceRef().isBlank()
                    && !realIds.contains(entry.getEvidenceRef())) {
                issues.add("Timeline entry evidenceRef '" + entry.getEvidenceRef()
                    + "' does not match any evidence item in the pack — this reference appears to be hallucinated.");
                risk = RiskLevel.HIGH;
            }
        }
        return risk;
    }

    private List<String> collectAllText(PostmortemDraftResponse draft) {
        List<String> texts = new ArrayList<>();
        if (draft.getIncidentSummary() != null) texts.add(draft.getIncidentSummary());
        if (draft.getLikelyCause()      != null) texts.add(draft.getLikelyCause());
        if (draft.getImpact()           != null) texts.add(draft.getImpact());
        if (draft.getDataGaps()         != null) texts.addAll(draft.getDataGaps());
        if (draft.getFollowUpActions()  != null) texts.addAll(draft.getFollowUpActions());
        if (draft.getTimeline()         != null) {
            draft.getTimeline().stream()
                .map(TimelineEntry::getDescription)
                .filter(d -> d != null)
                .forEach(texts::add);
        }
        return texts;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private RiskLevel max(RiskLevel a, RiskLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
