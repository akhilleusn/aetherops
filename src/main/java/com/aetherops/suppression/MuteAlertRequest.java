package com.aetherops.suppression;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Phase 2 remediation, finding 12 (see STEP_REPORT.md): {@code @Size} limits below match
 * {@code alert_suppression_rules}' actual column widths (V52__create_alert_suppression_rules.sql)
 * exactly, so an oversized value is rejected (400) rather than hitting an uncaught constraint
 * violation. All four columns are nullable, so {@code @Size} alone (no {@code @NotBlank}) is
 * correct here — {@code @Size} only validates length when a value is present.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MuteAlertRequest {

    /** Null mutes every service for this tenant. */
    @Size(max = 100, message = "serviceId must not exceed 100 characters")
    private String serviceId;

    /** Null mutes every alert name for the matched service. */
    @Size(max = 255, message = "alertName must not exceed 255 characters")
    private String alertName;

    /** Null mutes indefinitely, until explicitly unmuted. */
    private Instant mutedUntil;

    @Size(max = 500, message = "reason must not exceed 500 characters")
    private String reason;

    @Size(max = 255, message = "createdBy must not exceed 255 characters")
    private String createdBy;
}
