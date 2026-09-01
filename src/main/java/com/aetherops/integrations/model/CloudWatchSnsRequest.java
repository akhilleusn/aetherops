package com.aetherops.integrations.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AWS SNS HTTP(S) subscription envelope — CloudWatch Alarms are normally delivered via an
 * SNS topic, and SNS itself (not CloudWatch) is what POSTs to our endpoint. The actual alarm
 * payload arrives JSON-encoded as a string inside {@link #getMessage()}. AWS's real payload
 * uses PascalCase keys ("Type", not "type") — pinned explicitly with @JsonProperty since
 * Jackson's default getter-based property naming would otherwise expect lowercase-first keys
 * and silently fail to bind them (see CloudWatchAlarmMessage for the same issue).
 */
@Data
@NoArgsConstructor
public class CloudWatchSnsRequest {

    /** "Notification" or "SubscriptionConfirmation". */
    @JsonProperty("Type")
    private String type;

    /** For a Notification: the JSON-encoded CloudWatch alarm state-change payload. */
    @JsonProperty("Message")
    private String message;

    /** For a SubscriptionConfirmation: the URL to GET to confirm the subscription. */
    @JsonProperty("SubscribeURL")
    private String subscribeURL;
}
