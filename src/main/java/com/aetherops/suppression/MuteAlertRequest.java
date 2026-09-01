package com.aetherops.suppression;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MuteAlertRequest {

    /** Null mutes every service for this tenant. */
    private String serviceId;

    /** Null mutes every alert name for the matched service. */
    private String alertName;

    /** Null mutes indefinitely, until explicitly unmuted. */
    private Instant mutedUntil;

    private String reason;

    private String createdBy;
}
