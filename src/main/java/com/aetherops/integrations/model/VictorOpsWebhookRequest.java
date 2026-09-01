package com.aetherops.integrations.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/** VictorOps (Splunk On-Call) outbound webhook payload. */
@Data
@NoArgsConstructor
public class VictorOpsWebhookRequest {

    /** CRITICAL, WARNING, INFO, or RECOVERY. */
    private String message_type;

    private String entity_id;
    private String entity_display_name;
    private String state_message;
}
