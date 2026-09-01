package com.aetherops.integrations.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/** LaunchDarkly audit-log webhook payload (subset used for flag-change correlation). */
@Data
@NoArgsConstructor
public class LaunchDarklyWebhookRequest {

    private String kind;
    private String titleVerb;
    /** The flag key. */
    private String name;
    private String shortDescription;
    private Member member;
    /** Epoch millis. */
    private Long date;

    @Data
    @NoArgsConstructor
    public static class Member {
        private String email;
    }
}
