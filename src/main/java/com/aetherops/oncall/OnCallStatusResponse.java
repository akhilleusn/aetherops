package com.aetherops.oncall;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OnCallStatusResponse {
    private String serviceId;
    private String rotationName;
    /** Last 4 digits only — see SlackCommandController for the same masking convention. */
    private String currentOnCallMasked;
}
