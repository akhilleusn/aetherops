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

    /** Present on every message type; part of the signed "string to sign". */
    @JsonProperty("MessageId")
    private String messageId;

    @JsonProperty("TopicArn")
    private String topicArn;

    @JsonProperty("Timestamp")
    private String timestamp;

    /** Notification only, and only if the SNS publish included one. */
    @JsonProperty("Subject")
    private String subject;

    /** SubscriptionConfirmation/UnsubscribeConfirmation only. */
    @JsonProperty("Token")
    private String token;

    /** Base64-encoded signature over the canonical "string to sign". */
    @JsonProperty("Signature")
    private String signature;

    /** URL of the X.509 certificate to verify Signature with — must be validated as a
     *  genuine AWS SNS host (see AwsSnsHostValidator) before ever being fetched. */
    @JsonProperty("SigningCertURL")
    private String signingCertURL;

    /** "1" (SHA1) or "2" (SHA256) — selects the signature algorithm. */
    @JsonProperty("SignatureVersion")
    private String signatureVersion;
}
