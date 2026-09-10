package com.aetherops.integrations.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LaunchDarkly audit-log webhook payload (subset used for flag-change correlation).
 *
 * <p>Phase 2 remediation, finding 3 (see STEP_REPORT.md): {@code name} is stored as
 * {@code LaunchDarklyFlagEvent.flagKey} (VARCHAR(255) NOT NULL) and {@code member.email} as
 * {@code changedBy} (VARCHAR(255)) — both bounded here so an oversized value is rejected (400).
 * {@code shortDescription}/{@code titleVerb} are stored as {@code changeDescription} (TEXT,
 * unbounded) and need no constraint.
 */
@Data
@NoArgsConstructor
public class LaunchDarklyWebhookRequest {

    private String kind;
    private String titleVerb;
    /** The flag key. */
    @Size(max = 255, message = "name must not exceed 255 characters")
    private String name;
    private String shortDescription;
    @Valid
    private Member member;
    /** Epoch millis. */
    private Long date;

    @Data
    @NoArgsConstructor
    public static class Member {
        @Size(max = 255, message = "member.email must not exceed 255 characters")
        private String email;
    }
}
