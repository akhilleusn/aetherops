package com.aetherops.integrations.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The inner JSON payload of a CloudWatch alarm state-change notification, decoded from
 * CloudWatchSnsRequest.Message. AWS's real payload uses PascalCase keys ("AlarmName", not
 * "alarmName") — Jackson's default getter-based property naming would otherwise decapitalize
 * these ("getAlarmName()" -> expects "alarmName"), silently failing to bind the real field,
 * so each one is pinned explicitly with @JsonProperty.
 */
@Data
@NoArgsConstructor
public class CloudWatchAlarmMessage {

    @JsonProperty("AlarmName")
    private String alarmName;

    /** "ALARM" or "OK" (or "INSUFFICIENT_DATA"). */
    @JsonProperty("NewStateValue")
    private String newStateValue;

    @JsonProperty("NewStateReason")
    private String newStateReason;

    @JsonProperty("Trigger")
    private Trigger trigger;

    @Data
    @NoArgsConstructor
    public static class Trigger {
        @JsonProperty("Namespace")
        private String namespace;
    }
}
