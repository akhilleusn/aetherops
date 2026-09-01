package com.aetherops.integrations.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Splunk alert action "Webhook" payload. */
@Data
@NoArgsConstructor
public class SplunkWebhookRequest {

    @NotBlank(message = "search_name is required")
    private String search_name;

    private String sid;

    /** "1" (highest) through "5" (lowest), matching Splunk's severity scale. */
    private String severity;

    private Map<String, Object> result;
}
