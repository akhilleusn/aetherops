package com.aetherops.integrations.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Sentry issue-alert webhook ("Internal Integration" or legacy alert webhook) payload. */
@Data
@NoArgsConstructor
public class SentryWebhookRequest {

    /** "created" (new/reactivated issue) or "resolved". */
    private String action;

    private IssueData data;

    @Data
    @NoArgsConstructor
    public static class IssueData {
        private Issue issue;
    }

    @Data
    @NoArgsConstructor
    public static class Issue {
        private String title;
        private String culprit;
        /** fatal, error, warning, info, debug. */
        private String level;
        private String project;
    }
}
