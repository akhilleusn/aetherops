package com.aetherops.integrations.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sentry issue-alert webhook ("Internal Integration" or legacy alert webhook) payload.
 *
 * <p>Phase 2 remediation, finding 3 (see STEP_REPORT.md): {@code data.issue.project} is the
 * source of {@code Incident.serviceId} (VARCHAR(100) NOT NULL) — bounded here so an oversized
 * value is rejected (400), not left to hit an uncaught constraint violation. {@code
 * data.issue.title} is NOT length-constrained here — truncated in
 * {@code IntegrationsWebhookController} instead of rejected; see that controller's comment.
 */
@Data
@NoArgsConstructor
public class SentryWebhookRequest {

    /** "created" (new/reactivated issue) or "resolved". */
    private String action;

    @Valid
    private IssueData data;

    @Data
    @NoArgsConstructor
    public static class IssueData {
        @Valid
        private Issue issue;
    }

    @Data
    @NoArgsConstructor
    public static class Issue {
        private String title;
        private String culprit;
        /** fatal, error, warning, info, debug. */
        private String level;
        @Size(max = 100, message = "data.issue.project must not exceed 100 characters")
        private String project;
    }
}
